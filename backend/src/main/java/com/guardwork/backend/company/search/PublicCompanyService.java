package com.guardwork.backend.company.search;

import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.company.search.dto.CompanyCount;
import com.guardwork.backend.company.search.dto.CompanyDirectoryEntry;
import com.guardwork.backend.company.search.dto.CompanyDossierResponse;
import com.guardwork.backend.company.search.dto.CompanyRole;
import com.guardwork.backend.company.search.dto.CompanySalaryBand;
import com.guardwork.backend.company.search.dto.CompanySalaryRange;
import com.guardwork.backend.company.search.dto.CompanySummary;
import com.guardwork.backend.job.search.JobSearchService;
import com.guardwork.backend.job.service.JobPublicationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Function;

/**
 * Candidate-facing company discovery.
 *
 * <p>Company search deliberately delegates visibility, filtering and posting projection to the job
 * search module. This service only groups those public postings and derives screen-ready summaries;
 * it must not add company facts that the company has not published.
 */
@Service
@Transactional(readOnly = true)
public class PublicCompanyService {

  private final JobSearchService search;

  public PublicCompanyService(JobSearchService search) {
    this.search = search;
  }

  public PageResponse<CompanyDirectoryEntry> search(String keyword, Pageable pageable) {
    PageResponse<JobSearchService.CompanyGroup> groups = search.searchCompanies(keyword, pageable);
    List<Long> companyIds = groups.content().stream().map(JobSearchService.CompanyGroup::id).toList();
    Map<Long, List<JobPublicationService.PublicDetail>> byCompany =
        search.publicDetailsForCompanies(companyIds).stream()
            .collect(Collectors.groupingBy(detail -> detail.company().id(), LinkedHashMap::new,
                Collectors.toList()));

    List<CompanyDirectoryEntry> content =
        groups.content().stream()
            .map(
                group ->
                    new CompanyDirectoryEntry(
                        group.id(),
                        group.name(),
                        summarize(roles(byCompany.getOrDefault(group.id(), List.of())))))
            .toList();

    return new PageResponse<>(
        content,
        groups.pageNumber(),
        groups.pageSize(),
        groups.totalElements(),
        groups.totalPages(),
        groups.first(),
        groups.last());
  }

  public CompanyDossierResponse dossier(Long companyId) {
    return info(visiblePostings(companyId));
  }

  public PageResponse<CompanyRole> roles(Long companyId, Pageable pageable) {
    List<JobPublicationService.PublicDetail> postings = visiblePostings(companyId);
    List<CompanyRole> roles = roles(postings);
    Pageable bounded = PaginationUtils.validateAndBound(pageable);
    int offset = Math.toIntExact(bounded.getOffset());
    int size = Math.toIntExact(bounded.getPageSize());
    List<CompanyRole> paged = roles.subList(offset, Math.min(roles.size(), offset + size));
    Page<CompanyRole> page = new PageImpl<>(paged, bounded, roles.size());
    return PaginationUtils.toPageResponse(page, Function.identity());
  }

  private CompanyDossierResponse info(List<JobPublicationService.PublicDetail> postings) {
    List<CompanyRole> roles = roles(postings);
    return new CompanyDossierResponse(
        postings.getFirst().company().id(),
        postings.getFirst().company().name(),
        summarize(roles));
  }

  private List<JobPublicationService.PublicDetail> visiblePostings(Long companyId) {
    if (companyId == null || companyId <= 0) throw notFound();
    List<JobPublicationService.PublicDetail> postings =
        search.publicDetailsForCompanies(List.of(companyId));
    if (postings.isEmpty()) throw notFound();
    return postings;
  }

  private List<CompanyRole> roles(List<JobPublicationService.PublicDetail> postings) {
    List<CompanyRole> roles = new ArrayList<>(postings.size());
    for (JobPublicationService.PublicDetail posting : postings) {
      Map<String, Object> content = posting.content();
      roles.add(
          new CompanyRole(
              posting.id(),
              text(content, "title"),
              text(content, "location"),
              text(content, "industry"),
              text(content, "employmentType"),
              text(content, "workArrangement"),
              salary(content.get("compensation")),
              headcount(content.get("headcount")),
              deadline(posting),
              text(content, "description"),
              posting.applicationsAvailable()));
    }
    roles.sort(
        Comparator.comparing(
                CompanyRole::deadline, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CompanyRole::id));
    return roles;
  }

  private CompanySummary summarize(List<CompanyRole> roles) {
    Map<String, Integer> locations = new LinkedHashMap<>();
    Map<String, Integer> industries = new LinkedHashMap<>();
    Map<String, Integer> employmentTypes = new LinkedHashMap<>();
    Map<String, Integer> workArrangements = new LinkedHashMap<>();
    Map<String, SalaryBandAccumulator> bands = new LinkedHashMap<>();
    Set<String> deadlines = new TreeSet<>();
    int disclosedSalaryCount = 0;
    int headcountTotal = 0;

    for (CompanyRole role : roles) {
      addCount(locations, role.location());
      addCount(industries, role.industry());
      addCount(employmentTypes, role.employmentType());
      addCount(workArrangements, role.workArrangement());
      if (role.headcount() != null) headcountTotal += role.headcount();
      if (role.deadline() != null) deadlines.add(role.deadline());
      if (role.salary() == null) continue;
      disclosedSalaryCount++;
      String key = role.salary().currency() + '\0' + role.salary().payPeriod();
      bands.computeIfAbsent(key, ignored -> new SalaryBandAccumulator()).add(role.salary());
    }

    List<CompanySalaryBand> salaryBands =
        bands.values().stream()
            .map(SalaryBandAccumulator::band)
            .sorted(
                Comparator.comparingInt(CompanySalaryBand::count)
                    .reversed()
                    .thenComparing(CompanySalaryBand::minimum))
            .toList();

    return new CompanySummary(
        roles.size(),
        toCounts(locations),
        toCounts(industries),
        toCounts(employmentTypes),
        toCounts(workArrangements),
        salaryBands,
        disclosedSalaryCount,
        headcountTotal,
        List.copyOf(deadlines));
  }

  private static void addCount(Map<String, Integer> counts, String label) {
    if (label == null || label.isBlank()) return;
    counts.merge(label, 1, Integer::sum);
  }

  private static List<CompanyCount> toCounts(Map<String, Integer> counts) {
    return counts.entrySet().stream()
        .map(entry -> new CompanyCount(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparingInt(CompanyCount::count)
                .reversed()
                .thenComparing(CompanyCount::label))
        .toList();
  }

  private static CompanySalaryRange salary(Object value) {
    if (!(value instanceof Map<?, ?> compensation)) return null;
    if (!Boolean.TRUE.equals(compensation.get("disclosed"))) return null;
    BigDecimal minimum = decimal(compensation.get("minimum"));
    BigDecimal maximum = decimal(compensation.get("maximum"));
    if (minimum == null || maximum == null) return null;
    String currency = compensation.get("currency") instanceof String text ? text : "VND";
    String payPeriod =
        compensation.get("payPeriod") instanceof String text ? text : "MONTH";
    return new CompanySalaryRange(minimum, maximum, currency, payPeriod);
  }

  private static Integer headcount(Object value) {
    if (!(value instanceof Number number)) return null;
    int headcount = number.intValue();
    return headcount > 0 ? headcount : null;
  }

  private static BigDecimal decimal(Object value) {
    if (!(value instanceof Number number)) return null;
    return new BigDecimal(number.toString());
  }

  private static String deadline(JobPublicationService.PublicDetail posting) {
    Instant approvedDeadline = posting.approvedDeadline();
    if (approvedDeadline != null) {
      return approvedDeadline.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
    Object raw = posting.content().get("deadline");
    if (!(raw instanceof String value) || value.isBlank()) return null;
    try {
      return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate().toString();
    } catch (RuntimeException ignored) {
      // DraftContent may retain an offset form for legacy rows. Fall through to tolerant parsing.
    }
    try {
      return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
    } catch (RuntimeException ignored) {
      return value.matches("\\d{4}-\\d{2}-\\d{2}.*") ? value.substring(0, 10) : null;
    }
  }

  private static String text(Map<String, Object> content, String field) {
    Object value = content.get(field);
    if (!(value instanceof String text)) return null;
    String trimmed = text.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "40400 COMPANY_NOT_FOUND: Company not found");
  }

  private static final class SalaryBandAccumulator {
    private BigDecimal minimum;
    private BigDecimal maximum;
    private String currency;
    private String payPeriod;
    private int count;

    void add(CompanySalaryRange salary) {
      if (minimum == null || salary.minimum().compareTo(minimum) < 0) minimum = salary.minimum();
      if (maximum == null || salary.maximum().compareTo(maximum) > 0) maximum = salary.maximum();
      currency = salary.currency();
      payPeriod = salary.payPeriod();
      count++;
    }

    CompanySalaryBand band() {
      return new CompanySalaryBand(currency, payPeriod, minimum, maximum, count);
    }
  }
}
