package com.guardwork.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class UserReportHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper json;
  MockMvc http;

  record Account(long id, String token) {}

  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  Account account(String role) {
    String name = "rep_" + UUID.randomUUID().toString().replace("-", "");
    long id = db.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role,status) "
        + "VALUES ('An','Nguyen',?,?,'unused',?,'ACTIVE') RETURNING id", Long.class,
        name, name + "@test.invalid", role);
    return new Account(id, "Bearer " + jwt.generateAccessToken(id, name, name + "@test.invalid", role));
  }

  long company() {
    return db.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url,verification_status) "
        + "VALUES ('Reported Company',?,'retained','VERIFIED') RETURNING id", Long.class, UUID.randomUUID().toString());
  }

  MockMultipartFile report(String type, String id, String complaint) throws Exception {
    return new MockMultipartFile("report", "", "application/json",
        json.writeValueAsBytes(Map.of("targetType", type, "targetId", id, "complaint", complaint)));
  }

  long job(long company, Account creator, Account staff) {
    long id = db.queryForObject("INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at) "
        + "VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id", Long.class, company, creator.id());
    long revision = db.queryForObject("INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,"
        + "created_at,submitter_user_id,submitted_at,reviewer_user_id,decided_at) "
        + "VALUES (?,1,'{\"title\":\"Approved public title\",\"deadline\":\"2099-01-01T00:00:00Z\"}'::jsonb,'APPROVED',?,now(),?,now(),?,now()) RETURNING id",
        Long.class, id, creator.id(), creator.id(), staff.id());
    db.update("UPDATE job_postings SET status='PUBLISHED',published_revision_id=?,approved_deadline='2099-01-01T00:00:00Z' WHERE id=?", revision, id);
    return id;
  }

  @Test void submittedReportIsPrivatelyInspectableByStaffWithoutStartingReview() throws Exception {
    var user = account("USER");
    var staff = account("STAFF");
    String complaint = "Thông tin công ty có dấu hiệu không chính xác";
    var response = http.perform(multipart("/api/reports")
        .file(report("COMPANY", "" + company(), complaint)).header("Authorization", user.token()))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("OPEN"))
        .andExpect(jsonPath("$.data.reporter").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(response).path("data").path("id").asLong();
    for (int read = 0; read < 2; read++) {
      http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint").value(complaint))
          .andExpect(jsonPath("$.data.status").value("OPEN"))
          .andExpect(jsonPath("$.data.reporter.userId").value(user.id()))
          .andExpect(jsonPath("$.data.reporter.email").doesNotExist())
          .andExpect(jsonPath("$.data.reporter.password").doesNotExist())
          .andExpect(jsonPath("$.data.targetLabel").value("Reported Company"))
          .andExpect(jsonPath("$.data.evidence").isEmpty());
    }
    http.perform(get("/api/staff/reports").param("targetType", "COMPANY").param("status", "OPEN")
        .header("Authorization", staff.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[?(@.id == " + id + ")].complaint").value(complaint));
  }

  @Test void evidenceIsRetainedPrivatelyAndDownloadedOnlyWithinItsReport() throws Exception {
    var user = account("USER"); var staff = account("STAFF");
    byte[] bytes = "%PDF-1.7\nReport evidence".getBytes(StandardCharsets.US_ASCII);
    var result = http.perform(multipart("/api/reports")
        .file(report("COMPANY", "" + company(), "Misleading company information"))
        .file(new MockMultipartFile("file", "../../proof.pdf", "application/pdf", bytes))
        .header("Authorization", user.token())).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(result).path("data").path("id").asLong();
    var detail = http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidence[0].filename").value("proof.pdf"))
        .andExpect(jsonPath("$.data.evidence[0].available").value(true))
        .andExpect(jsonPath("$.data.evidence[0].content").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    String download = json.readTree(detail).path("data").path("evidence").get(0).path("downloadPath").asText();
    http.perform(get(download).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(content().bytes(bytes))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
    http.perform(get(download).header("Authorization", user.token())).andExpect(status().isForbidden());
    http.perform(get(download)).andExpect(status().isUnauthorized());
    http.perform(get(download.replace("/reports/" + id + "/", "/reports/9223372036854775807/"))
        .header("Authorization", staff.token())).andExpect(status().isNotFound());
  }

  @Test void publicCandidateCanBeReportedAndLaterPrivacyChangesDoNotEraseTheComplaint() throws Exception {
    var reporter = account("USER"); var candidate = account("USER"); var staff = account("STAFF");
    UUID profile = UUID.randomUUID();
    db.update("INSERT INTO candidate_profiles(id,user_id,is_public) VALUES (?, ?, true)", profile, candidate.id());
    String response = http.perform(multipart("/api/reports")
        .file(report("CANDIDATE", profile.toString(), "Candidate identity appears misleading"))
        .header("Authorization", reporter.token())).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(response).path("data").path("id").asLong();
    http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetLabel").value("An Nguyen"))
        .andExpect(jsonPath("$.data.targetAvailable").value(true));
    db.update("UPDATE candidate_profiles SET is_public=false WHERE id=?", profile);
    http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetAvailable").value(false))
        .andExpect(jsonPath("$.data.targetLabel").isEmpty())
        .andExpect(jsonPath("$.data.complaint").value("Candidate identity appears misleading"));
    http.perform(multipart("/api/reports")
        .file(report("CANDIDATE", profile.toString(), "Candidate identity appears misleading"))
        .header("Authorization", reporter.token())).andExpect(status().isNotFound());
  }

  @Test void jobReportsUseApprovedTitleAndBecomeUnavailableWhenStaffHidesTheJob() throws Exception {
    var user = account("USER"); var staff = account("STAFF");
    long job = job(company(), user, staff);
    http.perform(get("/api/jobs/" + job)).andExpect(status().isOk());
    String response = http.perform(multipart("/api/reports")
        .file(report("JOB", "" + job, "Job description appears misleading"))
        .header("Authorization", user.token())).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(response).path("data").path("id").asLong();
    http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetLabel").value("Approved public title"));
    http.perform(put("/api/staff/jobs/" + job + "/hide").header("Authorization", staff.token())
        .contentType("application/json").content("{\"expectedJobVersion\":0,\"reason\":\"Misleading posting\"}"))
        .andExpect(status().isOk());
    http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetAvailable").value(false))
        .andExpect(jsonPath("$.data.targetLabel").isEmpty());
    http.perform(multipart("/api/reports").file(report("JOB", "" + job, "Job description appears misleading"))
        .header("Authorization", user.token())).andExpect(status().isNotFound());
  }

  @Test void reportJsonPartIsBoundedBeforeParsingEvenWhenExtraBytesAreWhitespace() throws Exception {
    var user = account("USER");
    String body = json.writeValueAsString(Map.of("targetType", "COMPANY", "targetId", "" + company(),
        "complaint", "Misleading company information")) + " ".repeat(32768);
    http.perform(multipart("/api/reports").file(new MockMultipartFile("report", "", "application/json",
        body.getBytes(StandardCharsets.UTF_8))).header("Authorization", user.token()))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").value("REPORT_CONTENT_TOO_LARGE"));
  }

  @Test void authenticationPortalAndRoleCannotBeSuppliedByTheClient() throws Exception {
    var user = account("USER"); var staff = account("STAFF"); long target = company();
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information")))
        .andExpect(status().isUnauthorized());
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .header("Authorization", staff.token())).andExpect(status().isForbidden());
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .header("Authorization", user.token()).header("X-Guardian-Portal", "staff")).andExpect(status().isForbidden());
    http.perform(get("/api/staff/reports").header("Authorization", user.token()).header("X-Staff-Role", "STAFF"))
        .andExpect(status().isForbidden());
    http.perform(get("/api/staff/reports")).andExpect(status().isUnauthorized());
    http.perform(get("/api/staff/reports").header("Authorization", staff.token()).header("X-Guardian-Portal", "public"))
        .andExpect(status().isForbidden());
    db.update("UPDATE users SET status='BANNED' WHERE id=?", user.id());
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .header("Authorization", user.token())).andExpect(status().isUnauthorized());
  }

  @Test void invalidFieldsIdentifiersAndUnavailableTargetsHaveSafeFailures() throws Exception {
    var user = account("USER"); var staff = account("STAFF"); long target = company();
    for (String id : new String[] {"0", "-1", "01", "9223372036854775808", "abc"}) {
      http.perform(multipart("/api/reports").file(report("COMPANY", id, "Misleading company information"))
          .header("Authorization", user.token())).andExpect(status().isBadRequest());
    }
    for (String complaint : new String[] {"short", " ", "x".repeat(5001)}) {
      http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, complaint))
          .header("Authorization", user.token())).andExpect(status().isBadRequest());
    }
    var forged = new MockMultipartFile("report", "", "application/json", json.writeValueAsBytes(Map.of(
        "targetType", "COMPANY", "targetId", "" + target, "complaint", "Misleading company information", "reporterId", staff.id())));
    http.perform(multipart("/api/reports").file(forged).header("Authorization", user.token()))
        .andExpect(status().isBadRequest());
    http.perform(multipart("/api/reports").file(report("CANDIDATE", "1-1-1-1-1", "Misleading candidate information"))
        .header("Authorization", user.token())).andExpect(status().isBadRequest());
    db.update("UPDATE companies SET verification_status='PENDING' WHERE id=?", target);
    for (long id : new long[] {target, Long.MAX_VALUE}) {
      http.perform(multipart("/api/reports").file(report("COMPANY", "" + id, "Misleading company information"))
          .header("Authorization", user.token())).andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("REPORT_TARGET_UNAVAILABLE"));
    }
    http.perform(get("/api/staff/reports/9223372036854775807").header("Authorization", staff.token()))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("REPORT_NOT_FOUND"));
  }

  @Test void attachmentFormatCountAndSizeAreValidatedWithoutPartialReports() throws Exception {
    var user = account("USER"); long target = company();
    for (var file : new MockMultipartFile[] {
        new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]),
        new MockMultipartFile("file", "fake.pdf", "application/pdf", "Not a PDF".getBytes(StandardCharsets.US_ASCII)),
        new MockMultipartFile("file", "wrong-type.pdf", "image/jpeg", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))}) {
      http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
          .file(file).header("Authorization", user.token())).andExpect(status().isBadRequest());
    }
    var file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .file(file).file(file).header("Authorization", user.token())).andExpect(status().isBadRequest());
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .file(file).file(file).file(file).file(file).header("Authorization", user.token())).andExpect(status().isPayloadTooLarge());
    http.perform(multipart("/api/reports").file(report("COMPANY", "" + target, "Misleading company information"))
        .file(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]))
        .header("Authorization", user.token())).andExpect(status().isPayloadTooLarge());
  }

  @Test void filtersPaginationExistingOutcomesAndUnavailableEvidenceRemainReadOnly() throws Exception {
    var user = account("USER"); var staff = account("STAFF");
    var response = http.perform(multipart("/api/reports")
        .file(report("COMPANY", "" + company(), "Misleading company information"))
        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
        .header("Authorization", user.token())).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(response).path("data").path("id").asLong();
    db.update("UPDATE user_reports SET status='RESOLVED',processing_outcome='Retained outcome' WHERE id=?", id);
    db.update("UPDATE user_report_evidence SET content=NULL WHERE report_id=?", id);
    String first = http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.processingOutcome").value("Retained outcome"))
        .andExpect(jsonPath("$.data.evidence[0].available").value(false))
        .andExpect(jsonPath("$.data.evidence[0].downloadPath").isEmpty())
        .andReturn().getResponse().getContentAsString();
    String evidenceId = json.readTree(first).path("data").path("evidence").get(0).path("id").asText();
    http.perform(get("/api/staff/reports/" + id + "/evidence/" + evidenceId).header("Authorization", staff.token()))
        .andExpect(status().isNotFound());
    http.perform(get("/api/staff/reports/" + id).header("Authorization", staff.token()))
        .andExpect(content().json(first));
    http.perform(get("/api/staff/reports").param("status", "RESOLVED").param("targetType", "COMPANY")
        .header("Authorization", staff.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[?(@.id == " + id + ")].status").value("RESOLVED"));
    http.perform(get("/api/staff/reports").param("page", "1000000").param("size", "100")
        .header("Authorization", staff.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty());
    for (String query : new String[] {"status=INVALID", "targetType=USER", "page=-1", "size=101", "page=2147483647&size=100"}) {
      http.perform(get("/api/staff/reports?" + query).header("Authorization", staff.token())).andExpect(status().isBadRequest());
    }
  }

  @Test void databaseAuditFailureRollsBackSubmissionAndEvidence() throws Exception {
    var user = account("USER"); var staff = account("STAFF");
    String before = http.perform(get("/api/staff/reports").header("Authorization", staff.token()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "gw99_audit_failure_" + suffix;
    String trigger = "gw99_audit_trigger_" + suffix;
    db.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
        + "IF NEW.action='REPORT_SUBMITTED' AND NEW.admin_id=" + user.id()
        + " THEN RAISE EXCEPTION 'Test audit unavailable'; END IF; RETURN NEW; END $$");
    db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION " + function + "()");
    try {
      http.perform(multipart("/api/reports").file(report("COMPANY", "" + company(), "Misleading company information"))
          .file(new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
          .header("Authorization", user.token())).andExpect(status().isInternalServerError());
      String after = http.perform(get("/api/staff/reports").header("Authorization", staff.token()))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      assertThat(json.readTree(after).path("data")).isEqualTo(json.readTree(before).path("data"));
    } finally {
      db.execute("DROP TRIGGER " + trigger + " ON audit_logs");
      db.execute("DROP FUNCTION " + function + "()");
    }
  }

  @Test void expiredCompanyBanDoesNotMakeAReportTargetUnavailableOrSettleItsStateOnRead() throws Exception {
    var user = account("USER"); var staff = account("STAFF"); long company = company();
    String response = http.perform(multipart("/api/reports")
        .file(report("COMPANY", "" + company, "Misleading company information"))
        .header("Authorization", user.token())).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long reportId = json.readTree(response).path("data").path("id").asLong();
    db.update("UPDATE companies SET is_banned=true WHERE id=?", company);
    db.update("INSERT INTO account_bans(company_id,reason,started_at,expires_at,created_by) "
        + "VALUES (?,'Expired restriction',now()-interval '2 hours',now()-interval '1 hour',?)", company, staff.id());
    http.perform(get("/api/staff/reports/" + reportId).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetAvailable").value(true));
    // Own-account inspection already exposes effective state; a report read must agree.
    http.perform(get("/api/staff/accounts/companies/" + company).header("Authorization", staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.isBanned").value(false));
    assertThat(db.queryForObject("SELECT is_banned FROM companies WHERE id=?", Boolean.class, company)).isTrue();
  }
}
