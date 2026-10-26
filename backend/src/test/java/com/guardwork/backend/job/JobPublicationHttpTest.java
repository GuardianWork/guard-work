package com.guardwork.backend.job;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"company.membership.notifications.worker-enabled=false", "jobs.expiry.worker-enabled=false"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(JobPublicationHttpTest.TimeConfig.class)
class JobPublicationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired MutableClock clock;
    MockMvc http;
    static final String JOBS = "/api/companies/me/jobs";
    @BeforeEach void setup() {
        clock.now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void ownCompanyStaffMemberPublishesOnlyApprovedSafeDetails() throws Exception {
        String owner=account(); long company=createdCompany(owner);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var d=create(owner,complete()); long job=d.path("id").asLong(), revision=d.path("workingRevision").path("id").asLong();
        http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound());
        var pending=mapper.readTree(http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner)
            .contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"APPROVED",null))).andExpect(status().isForbidden());
        String staff=staff(owner);
        http.perform(get("/api/admin/job-revisions").header("Authorization",staff)).andExpect(status().isNotFound());
        http.perform(get("/api/staff/job-revisions").header("Authorization",staff)
            .header("X-Guardian-Portal","public")).andExpect(status().isForbidden());
        http.perform(get("/api/staff/job-revisions").header("Authorization",staff)
            .header("X-Guardian-Portal","staff")).andExpect(status().isOk());
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"APPROVED",null)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("PUBLISHED"));
        http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.applicationsAvailable").value(true))
            .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai")).andExpect(jsonPath("$.data.company.taxCode").doesNotExist())
            .andExpect(jsonPath("$.data.assignments").doesNotExist()).andExpect(jsonPath("$.data.reviewerUserId").doesNotExist());
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"REJECTED","Needs correction"))).andExpect(status().isConflict());
        http.perform(get("/api/me/notifications").header("Authorization",staff)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.eventType == 'JOB_REVISION_APPROVED')]").isNotEmpty());
    }
    @Test void exactApprovedDeadlineExpiresWithOneFactEvenWhenWorkerIsDelayed() throws Exception {
        String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var c=complete();var deadline=clock.now.plusSeconds(30);c.put("deadline",deadline.toString());
        var d=create(owner,c);long job=d.path("id").asLong(),revision=d.path("workingRevision").path("id").asLong();
        var pending=mapper.readTree(http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff(owner)).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"APPROVED",null))).andExpect(status().isOk());
        clock.now=deadline.minusNanos(1);
        http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.applicationsAvailable").value(true));
        clock.now=deadline;
        http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED"))
          .andExpect(jsonPath("$.data.applicationAvailabilityReason").value("EXPIRED")).andExpect(jsonPath("$.data.applicationsAvailable").value(false));
        http.perform(get("/api/jobs/"+job)).andExpect(status().isOk());
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isEqualTo(1);
    }
    @Test void rejectionRetainsReviewedEvidenceAndRequiresTrimmedReason() throws Exception {
        String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var pending=pending(owner,complete());long job=pending.path("id").asLong(),revision=pending.path("workingRevision").path("id").asLong();String staff=staff(owner);
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"REJECTED"," tiny "))).andExpect(status().isBadRequest());
        database.update("UPDATE companies SET is_banned=true WHERE id=?",company);
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(pending,"REJECTED","  Please correct the location  "))).andExpect(status().isOk());
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.rejectionReason").value("Please correct the location")).andExpect(jsonPath("$.data.reviewerUserId").isNumber())
          .andExpect(jsonPath("$.data.decidedAt").isNotEmpty()).andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"));
        http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound());
        database.update("UPDATE companies SET is_banned=false WHERE id=?",company);
        http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/withdraw").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(confirm(pending,true))).andExpect(status().isConflict());
    }
    @Test void approvalRechecksCompanyResponsibilityDeadlineAndLiveStaffAuthority() throws Exception {
        String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());
        var pending=pending(owner,fields);long job=pending.path("id").asLong(),revision=pending.path("workingRevision").path("id").asLong();String staff=staff(owner);
        String route="/api/staff/job-revisions/"+revision+"/decision",body=decision(pending,"APPROVED",null);
        database.update("UPDATE users SET role='USER' WHERE id=?",userId(staff));
        http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        database.update("UPDATE users SET role='STAFF' WHERE id=?",userId(staff));
        database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?",company);
        http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        database.update("UPDATE job_postings SET owner_responsible=false WHERE id=?",job);
        http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        database.update("UPDATE job_postings SET owner_responsible=true WHERE id=?",job);clock.now=deadline;
        http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(jsonPath("$.data.state").value("PENDING_REVIEW"));
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_REVISION_APPROVED'",Long.class,job)).isZero();
    }
    @Test void restrictedSignedInReadersSeeSamePublicProjectionAndCompanySuspensionHidesIt() throws Exception {
        String owner=account(),reader=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var p=pending(owner,complete());long job=p.path("id").asLong(),revision=p.path("workingRevision").path("id").asLong();
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff(owner)).contentType(MediaType.APPLICATION_JSON).content(decision(p,"APPROVED",null))).andExpect(status().isOk());
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,true,'Reader')",userId(reader));
        String anonymous=http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String signed=http.perform(get("/api/jobs/"+job).header("Authorization",reader)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(signed).path("data")).isEqualTo(mapper.readTree(anonymous).path("data"));
        database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?",company);
        http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.applicationsAvailable").value(false)).andExpect(jsonPath("$.data.applicationAvailabilityReason").value("COMPANY_NOT_VERIFIED"));
        database.update("UPDATE companies SET is_banned=true WHERE id=?",company);
        http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound());
    }
    com.fasterxml.jackson.databind.JsonNode pending(String owner,Map<String,Object> content)throws Exception {
        var d=create(owner,content);return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit")
          .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Autowired com.guardwork.backend.job.service.JobExpiryService expiry;
    @Autowired com.guardwork.backend.notification.service.NotificationDispatcher dispatcher;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Test void failedDecisionRollsBackAllFactsAndNotificationDeliveryRecoversWithoutDuplicates() throws Exception {
        String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var p=pending(owner,complete());long job=p.path("id").asLong(),revision=p.path("workingRevision").path("id").asLong();String staff=staff(owner);
        String route="/api/staff/job-revisions/"+revision+"/decision",body=decision(p,"APPROVED",null);
        database.execute("CREATE FUNCTION fail_job04_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id="+job+" AND NEW.event_type='JOB_REVISION_APPROVED' THEN RAISE EXCEPTION 'controlled failure'; END IF; RETURN NEW; END $$");
        database.execute("CREATE TRIGGER fail_job04_audit BEFORE INSERT ON job_audit_events FOR EACH ROW EXECUTE FUNCTION fail_job04_audit()");
        try {http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isInternalServerError());}
        finally {database.execute("DROP TRIGGER fail_job04_audit ON job_audit_events");database.execute("DROP FUNCTION fail_job04_audit()");}
        http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound());
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(jsonPath("$.data.state").value("PENDING_REVIEW"));
        assertThat(database.queryForObject("SELECT count(*) FROM notification_outbox WHERE job_id=?",Long.class,job)).isZero();
        database.execute("CREATE FUNCTION fail_job04_delivery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id="+job+" THEN RAISE EXCEPTION 'controlled delivery failure'; END IF; RETURN NEW; END $$");
        database.execute("CREATE TRIGGER fail_job04_delivery BEFORE INSERT ON user_notifications FOR EACH ROW EXECUTE FUNCTION fail_job04_delivery()");
        try {http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());}
        finally {database.execute("DROP TRIGGER fail_job04_delivery ON user_notifications");database.execute("DROP FUNCTION fail_job04_delivery()");}
        http.perform(get("/api/jobs/"+job)).andExpect(status().isOk());
        assertThat(database.queryForObject("SELECT count(*) FROM user_notifications WHERE job_id=?",Long.class,job)).isZero();
        clock.now=clock.now.plusSeconds(31);dispatcher.dispatchDue();dispatcher.dispatchDue();
        http.perform(get("/api/me/notifications").header("Authorization",staff)).andExpect(jsonPath("$.data.content[?(@.jobId == "+job+")]").isNotEmpty());
        assertThat(database.queryForObject("SELECT count(*) FROM user_notifications WHERE job_id=?",Long.class,job)).isEqualTo(1);
    }
    @Test void requestRollbackDoesNotPersistExpiryAndWorkerCommitsItExactlyOnce() throws Exception {
        String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());var p=pending(owner,fields);
        long job=p.path("id").asLong(),revision=p.path("workingRevision").path("id").asLong();
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff(owner)).contentType(MediaType.APPLICATION_JSON).content(decision(p,"APPROVED",null))).andExpect(status().isOk());
        clock.now=deadline;
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            try {http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED"));}
            catch(Exception e){throw new RuntimeException(e);}tx.setRollbackOnly();
        });
        assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("PUBLISHED");
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isZero();
        expiry.expireDue();expiry.expireDue();
        http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED"));
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isEqualTo(1);
    }
    @Test void competingApprovalRejectionAndWithdrawalHaveExactlyOneWinner() throws Exception {
        for(String rival:java.util.List.of("REJECTED","WITHDRAWN")) {
          String owner=account(),moderator=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
          var p=pending(owner,complete());long job=p.path("id").asLong(),revision=p.path("workingRevision").path("id").asLong();String staff=staff(moderator);
          String route="/api/staff/job-revisions/"+revision+"/decision",approval=decision(p,"APPROVED",null),rejection=decision(p,"REJECTED","Correct the location");
          try(var connection=database.getDataSource().getConnection();var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
            var first=workers.submit(()->http.perform(put(route).header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(approval)).andReturn().getResponse().getStatus());
            String rivalToken=rival.equals("WITHDRAWN")?owner:staff(account());
            var second=workers.submit(()->rival.equals("WITHDRAWN")?
                http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/withdraw").header("Authorization",rivalToken).contentType(MediaType.APPLICATION_JSON).content(confirm(p,true))).andReturn().getResponse().getStatus():
                http.perform(put(route).header("Authorization",rivalToken).contentType(MediaType.APPLICATION_JSON).content(rejection)).andReturn().getResponse().getStatus());
            try {
              long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
              while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
                if(System.nanoTime()>until)throw new AssertionError("Competing requests did not reach Company lock");Thread.onSpinWait();
              }
              connection.commit();
            }finally{connection.rollback();}
            assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
          }
          assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type IN ('JOB_REVISION_APPROVED','JOB_REVISION_REJECTED','JOB_REVISION_WITHDRAWN')",Long.class,job)).isEqualTo(1);
        }
    }
    @Test void decisionsNotifyOwnerAndEachCurrentAssignedRecruiterOnce() throws Exception {
        String owner=account(),recruiter=account(),former=account();long company=createdCompany(owner),member=recruiter(company,recruiter),oldMember=recruiter(company,former);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var p=pending(recruiter,complete());long job=p.path("id").asLong(),revision=p.path("workingRevision").path("id").asLong();
        database.update("INSERT INTO job_assignments(job_id,company_id,membership_id,membership_joined_at,assigned_by_user_id,assigned_at,ended_at,end_reason,ended_by_user_id) SELECT ?,company_id,id,joined_at,?,now(),now(),'UNASSIGNED',user_id FROM company_memberships WHERE id=?",job,userId(owner),oldMember);
        http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",staff(owner)).contentType(MediaType.APPLICATION_JSON).content(decision(p,"APPROVED",null))).andExpect(status().isOk());
        http.perform(get("/api/me/notifications").header("Authorization",owner)).andExpect(jsonPath("$.data.content[?(@.eventType == 'JOB_REVISION_APPROVED')]").isNotEmpty());
        http.perform(get("/api/me/notifications").header("Authorization",recruiter)).andExpect(jsonPath("$.data.content[?(@.eventType == 'JOB_REVISION_APPROVED')]").isNotEmpty());
        http.perform(get("/api/me/notifications").header("Authorization",former)).andExpect(jsonPath("$.data.content[?(@.eventType == 'JOB_REVISION_APPROVED')]").isEmpty());
        assertThat(database.queryForObject("SELECT count(*) FROM user_notifications WHERE job_id=? AND event_type='JOB_REVISION_APPROVED'",Long.class,job)).isEqualTo(2);
    }
    String decision(com.fasterxml.jackson.databind.JsonNode pending,String outcome,String reason)throws Exception {
        var body=new java.util.LinkedHashMap<String,Object>(); body.put("expectedJobVersion",pending.path("version").asLong());
        body.put("expectedRevisionVersion",pending.path("workingRevision").path("version").asLong());body.put("outcome",outcome);if(reason!=null)body.put("reason",reason);
        return mapper.writeValueAsString(body);
    }
    String staff(String token)throws Exception {
        long id=userId(token);database.update("UPDATE users SET role='STAFF' WHERE id=?",id);
        String email=database.queryForObject("SELECT email FROM users WHERE id=?",String.class,id);
        return "Bearer "+mapper.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("usernameOrEmail",email,"password","Password123")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    Map<String,Object> complete() {
        var c=new java.util.LinkedHashMap<String,Object>();
        for(String f:java.util.List.of("title","responsibilities","requiredQualifications","preferredQualifications","experienceExpectations","benefits","location"))c.put(f,"Nội dung");
        c.put("description","Dòng một\nDòng hai"); c.put("applicationInstructions","Apply through GuardWork");
        c.put("employmentType","FULL_TIME");c.put("workArrangement","ONSITE");c.put("headcount",2);
        var questions=new java.util.ArrayList<Map<String,Object>>();
        for(String type:java.util.List.of("SHORT_TEXT","SINGLE_CHOICE","YES_NO","NUMERIC")) {
          var q=new java.util.LinkedHashMap<String,Object>();q.put("key",UUID.randomUUID().toString());q.put("type",type);
          q.put("prompt","Câu hỏi "+type);q.put("required",true);
          if(type.equals("SINGLE_CHOICE"))q.put("options",java.util.List.of("Một","Hai"));questions.add(q);
        }
        c.put("screeningQuestions",questions);
        c.put("deadline","2099-12-01T12:00:00+07:00");c.put("compensation",Map.of("disclosed",false));return c;
    }
    String confirm(com.fasterxml.jackson.databind.JsonNode d,boolean confirmed)throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion",d.path("version").asLong(),"expectedRevisionVersion",d.path("workingRevision").path("version").asLong(),"confirmed",confirmed));
    }

    @org.springframework.boot.test.context.TestConfiguration static class TimeConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary MutableClock jobTestClock(){return new MutableClock();}
    }
    static class MutableClock extends java.time.Clock {
        volatile java.time.Instant now=java.time.Instant.now();
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return this;}
        public java.time.Instant instant(){return now;}
    }

    long recruiter(long company, String token) throws Exception {
        return database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id",
                Long.class, company, userId(token));
    }

    com.fasterxml.jackson.databind.JsonNode create(String actor, Map<String, Object> content) throws Exception {
        return mapper.readTree(http.perform(post(JOBS).header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String saveBody(com.fasterxml.jackson.databind.JsonNode detail, Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion", detail.path("version").asLong(),
                "expectedRevisionVersion", detail.path("workingRevision").path("version").asLong(), "content", content));
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(post("/api/companies/verification-request").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(company(UUID.randomUUID().toString())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String account() throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
                                "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    }

    String company(String taxCode) throws Exception {
        return mapper.writeValueAsString(Map.of("name", "Công ty Giải Pháp Ánh Dương", "taxCode", taxCode,
                "email", taxCode + "@company.vn", "registrationCertificateUrl", "https://storage.guardwork.vn/giấy-phép.pdf"));
    }
}
