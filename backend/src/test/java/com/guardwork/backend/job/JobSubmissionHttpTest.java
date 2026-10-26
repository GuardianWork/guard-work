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

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(JobSubmissionHttpTest.TimeConfig.class)
class JobSubmissionHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired MutableClock clock;
    MockMvc http;
    static final String JOBS = "/api/companies/me/jobs";
    @BeforeEach void setup() {
        clock.now=java.time.Instant.now();
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void previewReportsIncompleteContentWithoutChangingDraft() throws Exception {
        String owner = account(); createdCompany(owner); var draft = create(owner, Map.of("title", "Bảo vệ"));
        http.perform(get(JOBS + "/" + draft.path("id").asLong() + "/draft/preview").header("Authorization", owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Bảo vệ"))
            .andExpect(jsonPath("$.data.submissionEligible").value(false))
            .andExpect(jsonPath("$.data.validationIssues[0].field").value("content.description"))
            .andExpect(jsonPath("$.data.jobVersion").value(draft.path("version").asLong()));
        http.perform(get(JOBS + "/" + draft.path("id").asLong()).header("Authorization", owner))
            .andExpect(jsonPath("$.data.workingRevision.state").value("DRAFT"))
            .andExpect(jsonPath("$.data.version").value(draft.path("version").asLong()));
    }

    @Test void exactConfirmedRevisionIsSubmittedOnceAndWithdrawalPreservesHistory() throws Exception {
        String owner=account(); long company=createdCompany(owner);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var draft=create(owner,complete()); long job=draft.path("id").asLong(), revision=draft.path("workingRevision").path("id").asLong();
        String route=JOBS+"/"+job+"/revisions/"+revision;
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionEligible").value(true))
            .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"));
        http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,false))).andExpect(status().isBadRequest());
        var pending=mapper.readTree(http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,true))).andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision.state").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.data.publishedRevisionId").isEmpty()).andReturn().getResponse().getContentAsString()).path("data");
        http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,true))).andExpect(status().isConflict());
        http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(saveBody(pending,Map.of("title","Changed")))).andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> database.update("UPDATE job_posting_revisions SET content='{}' WHERE id=?",revision))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String staff=account(); long staffId=userId(staff);
        database.update("UPDATE users SET role='STAFF' WHERE id=?",staffId);
        // Tokens must reflect the newly granted platform role; the live service check is additional protection.
        http.perform(get("/api/staff/job-revisions").header("Authorization",owner)).andExpect(status().isForbidden());
        String login=http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("usernameOrEmail",database.queryForObject("SELECT email FROM users WHERE id=?",String.class,staffId),"password","Password123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        staff="Bearer "+mapper.readTree(login).path("data").path("accessToken").asText();
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"))
            .andExpect(jsonPath("$.data.approvalEligible").value(true));
        var editable=create(owner,Map.of());
        http.perform(get("/api/staff/job-revisions/"+editable.path("workingRevision").path("id").asLong()).header("Authorization",staff))
            .andExpect(status().isNotFound());
        http.perform(get("/api/staff/jobs/"+job+"/revisions").header("Authorization",staff))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        http.perform(get("/api/staff/job-revisions?status=DRAFT").header("Authorization",staff)).andExpect(status().isBadRequest());
        http.perform(get("/api/staff/job-revisions?status=ALL&size=1").header("Authorization",staff)).andExpect(status().isOk());
        http.perform(post(route+"/withdraw").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(pending,true))).andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision").isEmpty());
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isConflict());
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("WITHDRAWN")).andExpect(jsonPath("$.data.withdrawnAt").isNotEmpty());
        http.perform(get("/api/staff/job-revisions?status=WITHDRAWN").header("Authorization",staff)).andExpect(status().isOk());
        assertThat(database.queryForObject("SELECT state FROM job_posting_revisions WHERE id=?",String.class,revision)).isEqualTo("WITHDRAWN");
        assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions WHERE job_id=?",Long.class,job)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type IN ('JOB_REVISION_SUBMITTED','JOB_REVISION_WITHDRAWN')",Long.class,job)).isEqualTo(2);
    }

    @Test void submissionRejectsIncompleteCompensationQuestionsAndCurrentEligibility() throws Exception {
        String owner=account(); long company=createdCompany(owner); var draft=create(owner,complete());
        String route=JOBS+"/"+draft.path("id").asLong()+"/revisions/"+draft.path("workingRevision").path("id").asLong()+"/submit";
        http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("COMPANY_VERIFICATION_REQUIRED")));
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        database.update("UPDATE job_postings SET owner_responsible=false WHERE id=?",draft.path("id").asLong());
        http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("JOB_RESPONSIBILITY_REQUIRED")));
        for (var fields:java.util.List.of(Map.<String,Object>of("compensation",Map.of("disclosed",false,"minimum",1)),
            Map.<String,Object>of("compensation",Map.of("disclosed",true,"minimum",5,"maximum",1,"currency","USD","payPeriod","MONTH")),
            Map.<String,Object>of("compensation",Map.of("disclosed",true,"minimum",1.5,"maximum",2,"currency","VND","payPeriod","MONTH")),
            Map.<String,Object>of("screeningQuestions",java.util.List.of(Map.of("key",UUID.randomUUID().toString(),"type","YES_NO","prompt","Question?","required",true,"options",java.util.List.of("a","b")))),
            Map.<String,Object>of("screeningQuestions",java.util.List.of(Map.of("type","SINGLE_CHOICE","prompt","Question?"))))) {
          var invalid=complete(); invalid.putAll(fields); var d=create(owner,invalid);
          http.perform(post(JOBS+"/"+d.path("id").asLong()+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isBadRequest());
        }
    }

    @Test void stalePreviewCannotSubmitEditedContentAndForeignRevisionsRemainConcealed() throws Exception {
        String owner=account(),other=account(); long company=createdCompany(owner);createdCompany(other);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var d=create(owner,complete());var foreign=create(other,complete());long job=d.path("id").asLong(),revision=d.path("workingRevision").path("id").asLong();
        var edited=complete();edited.put("title","Changed after preview");
        http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(saveBody(d,edited))).andExpect(status().isOk());
        http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true)))
            .andExpect(status().isConflict());
        http.perform(post(JOBS+"/"+job+"/revisions/"+foreign.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",other)).andExpect(status().isNotFound());
        http.perform(get(JOBS+"/"+job).header("Authorization",owner)).andExpect(jsonPath("$.data.workingRevision.state").value("DRAFT"))
            .andExpect(jsonPath("$.data.workingRevision.content.title").value("Changed after preview"));
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

    @Test void deadlineAtExactInstantFailsAndCompetingConfirmedSubmissionsHaveOneWinner() throws Exception {
        String owner=account(),recruiter=account(); long company=createdCompany(owner); recruiter(company,recruiter);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var c=complete(); c.put("deadline",clock.now.toString()); var expired=create(owner,c);
        http.perform(post(JOBS+"/"+expired.path("id").asLong()+"/revisions/"+expired.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(expired,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("JOB_DEADLINE_PASSED")));
        var d=create(recruiter,complete()); long job=d.path("id").asLong();
        String route=JOBS+"/"+job+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit", body=confirm(d,true);
        try(var connection=database.getDataSource().getConnection();var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
            var first=workers.submit(()->http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            var second=workers.submit(()->http.perform(post(route).header("Authorization",recruiter).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            try {
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
                if(System.nanoTime()>until) throw new AssertionError("Expected competing requests to wait for Company lock");
                Thread.sleep(10);
            }
            connection.commit();
            } finally { connection.rollback(); }
            assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
              .containsExactlyInAnyOrder(200,409);
        }
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_REVISION_SUBMITTED'",Long.class,job)).isEqualTo(1);
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
