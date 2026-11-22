package com.guardwork.backend.job.search;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.dto.JobSearchRequest;
import com.guardwork.backend.job.model.JobPosting;
import com.guardwork.backend.job.model.JobRevision;
import com.guardwork.backend.job.repository.JobPostingRepository;
import com.guardwork.backend.job.repository.JobRevisionRepository;
import com.guardwork.backend.job.service.DraftContent;
import com.guardwork.backend.job.service.JobPublicationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)

public class JobSearchService {

  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final CompanyRepository companies;
  private final com.guardwork.backend.job.service.DraftContent draftContent;
  private final Clock clock;

  public JobSearchService(
      JobPostingRepository jobs,
      JobRevisionRepository revisions,
      CompanyRepository companies,
      com.guardwork.backend.job.service.DraftContent draftContent,
      Clock clock) {
    this.jobs = jobs;
    this.revisions = revisions;
    this.companies = companies;
    this.draftContent = draftContent;
    this.clock = clock;
  }

  public record CompanyGroup(Long id, String name, long roleCount) {}

  public PageResponse<JobPublicationService.PublicDetail> search(JobSearchRequest request) {
    return search(request, org.springframework.data.domain.Pageable.unpaged());
  }

  public PageResponse<JobPublicationService.PublicDetail> search(JobSearchRequest request, Pageable pageable) {
    return search(request, pageable, null);
  }

  public PageResponse<JobPublicationService.PublicDetail> search(
      JobSearchRequest request, Pageable pageable, Long callerUserId) {
    pageable = PaginationUtils.validateAndBound(pageable);

    String normalizedKeyword = escapeLike(blankToNull(request.keyword()));
    String normalizedLocation = escapeLike(blankToNull(request.location()));
    String normalizedIndustry = escapeLike(blankToNull(request.industry()));
    String normalizedEmploymentType =
        validateEnum(request.employmentType(), Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP", "TEMPORARY"), "employmentType");
    String normalizedWorkArrangement =
        validateEnum(request.workArrangement(), Set.of("ONSITE", "HYBRID", "REMOTE"), "workArrangement");
    Instant deadlineInstant = parseDeadline(request.deadline());

    BigDecimal salaryMin = request.salaryMin();
    BigDecimal salaryMax = request.salaryMax();
    if (salaryMin != null && salaryMax != null && salaryMin.compareTo(salaryMax) > 0) {
      BigDecimal tmp = salaryMin;
      salaryMin = salaryMax;
      salaryMax = tmp;
    }

    Instant now = clock.instant();
    var ids =
        jobs.searchPublicJobIds(
            normalizedKeyword,
            normalizedLocation,
            normalizedIndustry,
            normalizedEmploymentType,
            normalizedWorkArrangement,
            salaryMin,
            salaryMax,
            deadlineInstant,
            now,
            callerUserId,
            pageable);

    List<Long> jobIds = ids.getContent();
    List<JobPublicationService.PublicDetail> content = loadDetails(jobIds);

    Page<JobPublicationService.PublicDetail> resultPage =
        new PageImpl<>(content, pageable, ids.getTotalElements());
    return PaginationUtils.toPageResponse(resultPage, item -> item);
  }

  /** Company search groups the same public postings that job search exposes. */
  public PageResponse<CompanyGroup> searchCompanies(String keyword, Pageable pageable) {
    pageable = PaginationUtils.validateAndBound(pageable);
    var page =
        jobs.searchPublicCompanyIds(escapeLike(blankToNull(keyword)), clock.instant(), pageable);
    return PaginationUtils.toPageResponse(
        page, row -> new CompanyGroup(row.getCompanyId(), row.getCompanyName(), row.getRoleCount()));
  }

  /** Every visible posting for the requested companies, in stable company/created order. */
  public List<JobPublicationService.PublicDetail> publicDetailsForCompanies(
      Collection<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) return List.of();
    List<Long> jobIds =
        jobs.searchPublicJobIdsForCompanies(List.copyOf(companyIds), clock.instant());
    return loadDetails(jobIds);
  }

  /**
   * Application totals for the given public postings, keyed by job id. Callers pass only ids from a
   * public projection, so applicants' counts never widen beyond what those postings expose. Postings
   * with no applications are absent from the result.
   */
  public Map<Long, Long> publicApplicationCounts(Collection<Long> jobIds) {
    if (jobIds == null || jobIds.isEmpty()) return Map.of();
    Map<Long, Long> counts = new HashMap<>();
    jobs.countApplicationsForJobs(List.copyOf(jobIds))
        .forEach(count -> counts.put(count.getJobId(), count.getTotal()));
    return counts;
  }

  private List<JobPublicationService.PublicDetail> loadDetails(List<Long> jobIds) {
    List<JobPublicationService.PublicDetail> content = new ArrayList<>();
    if (jobIds.isEmpty()) return content;

    Map<Long, JobPosting> postings = new TreeMap<>();
    for (JobPosting posting : jobs.findAllById(jobIds)) postings.put(posting.getId(), posting);

    Set<Long> revisionIds = new LinkedHashSet<>();
    Set<Long> companyIds = new LinkedHashSet<>();
    for (Long id : jobIds) {
      JobPosting posting = postings.get(id);
      if (posting != null && posting.getPublishedRevisionId() != null) revisionIds.add(posting.getPublishedRevisionId());
      if (posting != null) companyIds.add(posting.getCompanyId());
    }

    Map<Long, JobRevision> revisionsById = new java.util.HashMap<>();
    for (JobRevision revision : revisions.findAllById(revisionIds)) revisionsById.put(revision.getId(), revision);

    Map<Long, Company> companiesById = new java.util.HashMap<>();
    for (Company company : companies.findAllById(companyIds)) companiesById.put(company.getId(), company);

    for (Long id : jobIds) {
      JobPosting posting = postings.get(id);
      if (posting == null || posting.getPublishedRevisionId() == null) continue;
      JobRevision revision = revisionsById.get(posting.getPublishedRevisionId());
      if (revision == null) continue;
      Company company = companiesById.get(posting.getCompanyId());
      if (company == null) continue;
      content.add(
          new JobPublicationService.PublicDetail(
              posting.getId(),
              new JobPublicationService.CompanyIdentity(company.getId(), company.getName()),
              draftContent.decode(revision.getContent()),
              posting.getStatus(),
              posting.getApprovedDeadline(),
              true,
              null,
              null,
              revision.getId(),
              revision.getVersion()));
    }
    return content;
  }

  private static String validateEnum(String value, Set<String> allowed, String field) {
    if (value == null || value.strip().isEmpty()) return null;
    String normalized = value.strip().toUpperCase();
    if (!allowed.contains(normalized)) throw DraftContent.invalid(field);
    return normalized;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeLike(String value) {
    if (value == null) return null;
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Instant parseDeadline(String value) {
    if (value == null || value.strip().isEmpty()) return null;
    String trimmed = value.strip();
    try {
      return OffsetDateTime.parse(trimmed).toInstant();
    } catch (RuntimeException first) {
      try {
        return LocalDate.parse(trimmed).atTime(java.time.LocalTime.MAX).atOffset(java.time.ZoneOffset.UTC).toInstant();
      } catch (RuntimeException second) {
        throw DraftContent.invalid("deadline");
      }
    }
  }
}
