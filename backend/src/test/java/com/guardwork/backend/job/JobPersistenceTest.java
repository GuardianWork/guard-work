package com.guardwork.backend.job;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Supplements HTTP behavior at the approved PostgreSQL constraint seam. */
@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
class JobPersistenceTest {
  @Autowired JdbcTemplate db;

  @Test void oneWorkingRevisionAndSameJobReferencesAreEnforced() {
    long user = user(), company = company(), job = job(company, user), other = job(company, user);
    long revision = revision(job, user, 1);
    assertThatThrownBy(() -> revision(job, user, 2)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("UPDATE job_posting_revisions SET source_revision_id=? WHERE id=?", revision(other, user, 1), revision))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("UPDATE job_postings SET published_revision_id=?, approved_deadline=now(),status='PUBLISHED' WHERE id=?", revision, other))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("UPDATE job_postings SET published_revision_id=?, approved_deadline=now(),status='PUBLISHED' WHERE id=?", revision, job))
        .isInstanceOf(DataAccessException.class);
  }

  @Test void submittedEvidenceAndCompletedReviewAreImmutableAndWorkingSlotIsFreed() {
    long user = user(), company = company(), job = job(company, user), revision = revision(job, user, 1);
    submit(revision, user);
    for (String mutation : new String[] {"content='{}'", "submitter_user_id=null", "source_revision_id=" + revision,
        "creator_user_id=" + user(), "state='DRAFT'", "created_at=created_at+interval '1 second'"})
      assertThatThrownBy(() -> db.update("UPDATE job_posting_revisions SET " + mutation + " WHERE id=?", revision))
          .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM job_posting_revisions WHERE id=?", revision)).isInstanceOf(DataAccessException.class);
    db.update("UPDATE job_posting_revisions SET state='REJECTED',reviewer_user_id=?,decided_at=now(),rejection_reason='Please clarify requirements' WHERE id=?", user, revision);
    assertThatThrownBy(() -> db.update("UPDATE job_posting_revisions SET rejection_reason='A different explanation' WHERE id=?", revision)).isInstanceOf(DataAccessException.class);
    assertThat(revision(job, user, 2)).isPositive();
  }

  @Test void approvalPointerDeadlineAndTerminalJobStateStayConsistent() {
    long user = user(), company = company(), job = job(company, user), revision = revision(job, user, 1);
    submit(revision, user);
    db.update("UPDATE job_posting_revisions SET state='APPROVED',reviewer_user_id=?,decided_at=now() WHERE id=?", user, revision);
    assertThatThrownBy(() -> db.update("UPDATE job_postings SET published_revision_id=?,approved_deadline='2030-01-01T00:00:00Z',status='PUBLISHED' WHERE id=?", revision, job))
        .isInstanceOf(DataAccessException.class);
    db.update("UPDATE job_postings SET published_revision_id=?,approved_deadline='2031-01-01T00:00:00Z',status='PUBLISHED' WHERE id=?", revision, job);
    db.update("UPDATE job_postings SET status='CLOSED',closed_at=now() WHERE id=?", job);
    assertThatThrownBy(() -> db.update("UPDATE job_postings SET status='PUBLISHED',closed_at=null WHERE id=?", job)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM job_postings WHERE id=?", job)).isInstanceOf(DataAccessException.class);
  }

  @Test void assignmentRequiresSameCompanyCurrentRecruiterAndRetainsEndedHistory() {
    long user = user(), company = company(), job = job(company, user), foreign = company();
    long member = db.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id", Long.class, company, user);
    String insert = "INSERT INTO job_assignments(job_id,company_id,membership_id,membership_joined_at,assigned_by_user_id,assigned_at) SELECT ?,?,id,joined_at,?,now() FROM company_memberships WHERE id=? RETURNING id";
    long other = job(foreign, user);
    assertThatThrownBy(() -> db.queryForObject(insert, Long.class, other, foreign, user, member)).isInstanceOf(DataAccessException.class);
    long assignment = db.queryForObject(insert, Long.class, job, company, user, member);
    assertThatThrownBy(() -> db.queryForObject(insert, Long.class, job, company, user, member)).isInstanceOf(DataAccessException.class);
    db.update("UPDATE job_assignments SET ended_at=now(), ended_by_user_id=?,end_reason='UNASSIGNED' WHERE id=?", user, assignment);
    assertThatThrownBy(() -> db.update("UPDATE job_assignments SET ended_at=null,ended_by_user_id=null,end_reason=null WHERE id=?", assignment)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM job_assignments WHERE id=?", assignment)).isInstanceOf(DataAccessException.class);
    db.update("UPDATE company_memberships SET role='ADMIN' WHERE id=?", member);
    assertThatThrownBy(() -> db.queryForObject(insert, Long.class, job, company, user, member)).isInstanceOf(DataAccessException.class);
  }

  @Test void auditIsAppendOnlyAndLegacyNotificationRowsRemainValid() {
    long user = user(), company = company(), job = job(company, user);
    long audit = db.queryForObject("INSERT INTO job_audit_events(company_id,job_id,actor_user_id,event_type,created_at) VALUES (?,?,?,'JOB_CREATED',now()) RETURNING id", Long.class, company, job, user);
    assertThatThrownBy(() -> db.update("UPDATE job_audit_events SET facts='{}' WHERE id=?", audit)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM job_audit_events WHERE id=?", audit)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.execute("TRUNCATE job_audit_events")).isInstanceOf(DataAccessException.class);
    UUID event = UUID.randomUUID();
    db.update("INSERT INTO notification_outbox(event_id,recipient_user_id,event_type,company_id,company_name,created_at,next_attempt_at) VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Legacy',now(),now())", event, user, company);
    db.update("INSERT INTO user_notifications(source_event_id,recipient_user_id,event_type,company_id,company_name,created_at) VALUES (?,?,'MEMBERSHIP_REMOVED',?,'Legacy',now())", event, user, company);
  }

  @Test void notificationContextCannotBeRewrittenOrPointAcrossCompanies() {
    long user = user(), company = company(), job = job(company, user), other = company();
    UUID event = UUID.randomUUID();
    String insert = "INSERT INTO notification_outbox(event_id,recipient_user_id,event_type,company_id,company_name,job_id,created_at,next_attempt_at) VALUES (?,?,'JOB_ASSIGNED',?,'Company',?,now(),now())";
    assertThatThrownBy(() -> db.update(insert, UUID.randomUUID(), user, other, job)).isInstanceOf(DataAccessException.class);
    db.update(insert, event, user, company, job);
    assertThatThrownBy(() -> db.update("UPDATE notification_outbox SET job_id=null WHERE event_id=?", event)).isInstanceOf(DataAccessException.class);
    long notification = db.queryForObject("INSERT INTO user_notifications(source_event_id,recipient_user_id,event_type,company_id,company_name,job_id,created_at) VALUES (?,?,'JOB_ASSIGNED',?,'Company',?,now()) RETURNING id", Long.class, event, user, company, job);
    db.update("UPDATE user_notifications SET read_at=now() WHERE id=?", notification);
    assertThatThrownBy(() -> db.update("UPDATE user_notifications SET recipient_user_id=? WHERE id=?", user(), notification)).isInstanceOf(DataAccessException.class);
  }

  void submit(long revision, long user) {
    db.update("UPDATE job_posting_revisions SET state='PENDING_REVIEW',submitter_user_id=?,submitted_at=now() WHERE id=?", user, revision);
  }
  long revision(long job, long user, int number) {
    return db.queryForObject("INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,created_at) VALUES (?,?,'{\"title\":\"Reviewed\",\"deadline\":\"2031-01-01T00:00:00Z\"}','DRAFT',?,now()) RETURNING id", Long.class, job, number, user);
  }
  long job(long company, long user) {
    return db.queryForObject("INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at) VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id", Long.class, company, user);
  }
  long user() {
    String identity = "db_" + UUID.randomUUID().toString().replace("-", "");
    return db.queryForObject("INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id", Long.class, identity, identity + "@example.vn");
  }
  long company() {
    return db.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url) VALUES ('Company',?,'https://example.vn/license') RETURNING id", Long.class, UUID.randomUUID().toString());
  }
}
