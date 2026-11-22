package com.guardwork.backend.job.service;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import com.guardwork.backend.staff.service.AccountEnforcementService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shares the job-detail visibility boundary and Company-before-job lock order. */
@Service
@Transactional
public class JobContentVisibility {
  private final CompanyRepository companies;
  private final JobPostingRepository jobs;
  private final JobRevisionRepository revisions;
  private final AccountEnforcementService enforcement;
  private final JobExpiryService expiry;
  private final EntityManager entities;

  public JobContentVisibility(CompanyRepository companies, JobPostingRepository jobs,
      JobRevisionRepository revisions, AccountEnforcementService enforcement,
      JobExpiryService expiry, EntityManager entities) {
    this.companies = companies; this.jobs = jobs; this.revisions = revisions;
    this.enforcement = enforcement; this.expiry = expiry; this.entities = entities;
  }

  public record Locked(Company company, JobPosting job) {}

  public Locked lock(Long id) { return lock(id, null); }

  public Locked lock(Long id, Long actorCompanyId) {
    if (id == null || id <= 0) throw JobDraftService.notFound();
    var hint = jobs.findById(id).orElseThrow(JobDraftService::notFound);
    // Account enforcement also locks the actor's Company. Acquire both in a stable order
    // before any user/job lock when a recruiter analyzes another Company's public JD.
    Company company = null;
    var companyIds = new java.util.TreeSet<Long>();
    companyIds.add(hint.getCompanyId());
    if (actorCompanyId != null) companyIds.add(actorCompanyId);
    for (Long companyId : companyIds) {
      var locked = companies.findByIdForUpdate(companyId).orElseThrow(JobDraftService::notFound);
      if (companyId.equals(hint.getCompanyId())) company = locked;
    }
    enforcement.settleCompany(company);
    var job = jobs.lock(id, company.getId()).orElseThrow(JobDraftService::notFound);
    entities.refresh(job);
    return new Locked(company, job);
  }

  public JobRevision publishedRevision(Locked visible) {
    var job = visible.job();
    if (visible.company().isBanned() || job.getPublishedRevisionId() == null
        || job.isStaffSuspended() || "SUSPENDED".equals(job.getStatus()))
      throw JobDraftService.notFound();
    var revision = revisions.findById(job.getPublishedRevisionId())
        .filter(r -> "APPROVED".equals(r.getState())).orElseThrow(JobDraftService::notFound);
    expiry.reconcile(job);
    return revision;
  }
}
