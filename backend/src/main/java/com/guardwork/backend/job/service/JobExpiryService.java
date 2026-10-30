package com.guardwork.backend.job.service;

import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact-deadline reconciliation; callers hold the Company lock before the job lock. */
@Service
public class JobExpiryService {
  private final JobPostingRepository jobs;
  private final CompanyRepository companies;
  private final JobAuditEventRepository audit;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final int batch;
  @PersistenceContext private EntityManager entities;

  public JobExpiryService(
      JobPostingRepository jobs,
      CompanyRepository companies,
      JobAuditEventRepository audit,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${jobs.expiry.batch-size:100}") int batch) {
    if (batch < 1 || batch > 1000)
      throw new IllegalArgumentException("Expiry batch must be 1–1000");
    this.jobs = jobs;
    this.companies = companies;
    this.audit = audit;
    this.clock = clock;
    this.batch = batch;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public String effectiveStatus(JobPosting job) {
    return Set.of("PUBLISHED", "PAUSED").contains(job.getStatus())
            && job.getApprovedDeadline() != null
            && !clock.instant().isBefore(job.getApprovedDeadline())
        ? "EXPIRED"
        : job.getStatus();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean reconcile(JobPosting job) {
    if (!"EXPIRED".equals(effectiveStatus(job)) || "EXPIRED".equals(job.getStatus())) return false;
    String previous = job.getStatus();
    job.setStatus("EXPIRED");
    job.setExpiredAt(job.getApprovedDeadline());
    job.setUpdatedAt(clock.instant());
    jobs.flush();
    var fact = new JobAuditEvent();
    fact.setCompanyId(job.getCompanyId());
    fact.setJobId(job.getId());
    fact.setRevisionId(job.getPublishedRevisionId());
    fact.setEventType("JOB_EXPIRED");
    fact.setCreatedAt(clock.instant());
    fact.setFacts(
        Map.of(
            "previousStatus", previous, "approvedDeadline", job.getApprovedDeadline().toString()));
    audit.saveAndFlush(fact);
    return true;
  }

  /**
   * Bounded operational seam. Each item commits independently and always locks Company before job.
   */
  public int expireDue() {
    var due = jobs.findDue(clock.instant(), PageRequest.of(0, batch));
    int changed = 0;
    for (var item : due) {
      Boolean expired =
          transaction.execute(
              tx -> {
                if (companies.findByIdForUpdate(item.getCompanyId()).isEmpty()) return false;
                var job = jobs.lock(item.getId(), item.getCompanyId()).orElse(null);
                if (job == null) return false;
                entities.refresh(job);
                return reconcile(job);
              });
      if (Boolean.TRUE.equals(expired)) changed++;
    }
    return changed;
  }
}
