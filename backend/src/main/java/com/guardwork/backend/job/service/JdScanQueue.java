package com.guardwork.backend.job.service;

import com.guardwork.backend.job.model.*;
import com.guardwork.backend.job.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions only: provider calls happen in the worker, after claim commits. */
@Service
@Transactional
public class JdScanQueue {
  private final JdScanRunRepository runs;
  private final JdScanAttemptRepository attempts;
  private final Clock clock;

  public JdScanQueue(JdScanRunRepository runs, JdScanAttemptRepository attempts, Clock clock) {
    this.runs = runs; this.attempts = attempts; this.clock = clock;
  }

  public record Claim(Long id, UUID token, String analysisText, String mode, String profile,
      String legalSnapshot, LocalDate asOfDate) {}

  public Optional<Claim> claim() {
    Instant now = clock.instant();
    var selected = runs.claimable(now);
    if (selected.isEmpty()) return Optional.empty();
    var run = selected.get();
    if (run.getLeaseToken() != null) closeAttempt(run.getLeaseToken(), "LEASE_EXPIRED", now);
    if (run.getAttemptCount() >= 3) {
      terminalFailure(run, "LEASE_EXPIRED", now);
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    run.setStatus("RUNNING"); run.setLeaseToken(token); run.setLeaseUntil(now.plusSeconds(720));
    run.setAttemptCount(run.getAttemptCount() + 1); run.setFailureCode(null);
    var attempt = new JdScanAttempt(); attempt.setScanRunId(run.getId()); attempt.setClaimToken(token);
    attempt.setAttemptNumber(run.getAttemptCount()); attempt.setStartedAt(now); attempts.save(attempt);
    return Optional.of(new Claim(run.getId(), token, run.getAnalysisText(), run.getMode(),
        run.getProfile(), run.getLegalSnapshot(), run.getAsOfDate()));
  }

  public boolean complete(Claim claim, String report) {
    var run = runs.lock(claim.id()).orElseThrow();
    Instant now = clock.instant();
    if (!owns(run, claim, now)) return false;
    run.setReport(report); run.setStatus("COMPLETED"); run.setFinishedAt(now);
    run.setLeaseToken(null); run.setLeaseUntil(null); run.setFailureCode(null);
    closeAttempt(claim.token(), "COMPLETED", now);
    return true;
  }

  public boolean fail(Claim claim, boolean retryable) {
    var run = runs.lock(claim.id()).orElseThrow();
    Instant now = clock.instant();
    if (!owns(run, claim, now)) return false;
    String code = retryable ? "ADAPTER_UNAVAILABLE" : "INVALID_RESPONSE";
    closeAttempt(claim.token(), retryable ? "TRANSIENT_FAILURE" : "INVALID_RESPONSE", now);
    if (retryable && run.getAttemptCount() < 3) {
      run.setStatus("QUEUED"); run.setAvailableAt(now.plusSeconds(5L * run.getAttemptCount()));
      run.setLeaseToken(null); run.setLeaseUntil(null); run.setFailureCode(code);
    } else terminalFailure(run, code, now);
    return true;
  }

  private boolean owns(JdScanRun run, Claim claim, Instant now) {
    return "RUNNING".equals(run.getStatus()) && claim.token().equals(run.getLeaseToken())
        && run.getLeaseUntil().isAfter(now);
  }

  private void terminalFailure(JdScanRun run, String code, Instant now) {
    run.setStatus("FAILED"); run.setFailureCode(code); run.setFinishedAt(now);
    run.setLeaseToken(null); run.setLeaseUntil(null);
  }

  private void closeAttempt(UUID token, String outcome, Instant now) {
    var attempt = attempts.findByClaimToken(token).orElseThrow();
    attempt.setOutcome(outcome); attempt.setFinishedAt(now);
    attempt.setDurationMs(Math.max(0, Duration.between(attempt.getStartedAt(), now).toMillis()));
  }
}
