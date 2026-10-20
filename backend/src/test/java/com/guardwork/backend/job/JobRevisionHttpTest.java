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
@org.springframework.context.annotation.Import(JobRevisionHttpTest.TimeConfig.class)
class JobRevisionHttpTest {
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
    @Test void rejectedContentDerivesDistinctDraftAndPreservesFeedback() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var fields=complete();var d=create(owner,fields);long job=d.path("id").asLong(),source=d.path("workingRevision").path("id").asLong();
      var pending=submit(owner,d);String admin=admin(owner);
      http.perform(put("/api/admin/job-revisions/"+source+"/decision").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
        .content(decision(pending,"REJECTED","Please correct the benefits"))).andExpect(status().isOk());
      var current=inspect(owner,job);
      var derived=mapper.readTree(http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",current.path("version").asLong()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision.revisionNumber").value(2))
        .andReturn().getResponse().getContentAsString()).path("data");
      assertThat(derived.path("workingRevision").path("id").asLong()).isNotEqualTo(source);
      assertThat(derived.path("workingRevision").path("content")).isEqualTo(d.path("workingRevision").path("content"));
      http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.content[0].state").value("REJECTED"))
        .andExpect(jsonPath("$.data.content[0].rejectionReason").value("Please correct the benefits"))
        .andExpect(jsonPath("$.data.content[1].sourceRevisionId").value(source));
      http.perform(get("/api/admin/jobs/"+job+"/revisions").header("Authorization",admin))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
      var evidence=history(owner,job).get(0);
      fields.put("title","Corrected benefits");var corrected=save(owner,derived,fields);var resubmitted=submit(owner,corrected);
      decide(admin,resubmitted,"APPROVED");
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Corrected benefits"));
      assertThat(history(owner,job).get(0)).isEqualTo(evidence);
      assertThat(history(owner,job).get(1).path("state").asText()).isEqualTo("APPROVED");

    }
    @Test void personallyVerifiedMembersCanPrepareAllKnownJobRoutesBeforeCompanyVerification() throws Exception {
      String owner=account();long company=createdCompany(owner),user=userId(owner);
      database.update("INSERT INTO employer_profiles(user_id,applicant,full_name,email_verified_at,phone,phone_verified_at) VALUES (?,true,'Owner',now(),?,now())",user,"09"+user);
      var draft=create(owner,complete());long job=draft.path("id").asLong(),revision=draft.path("workingRevision").path("id").asLong();
      http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(put(JOBS+"/"+job+"/owner-responsibility").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",draft.path("version").asLong(),"responsible",true)))).andExpect(status().isOk());
      String recruit=account();long membership=recruiter(company,recruit);
      http.perform(put(JOBS+"/"+job+"/assignments/"+membership).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",draft.path("version").asLong())))).andExpect(status().isOk());
      var current=inspect(owner,job);
      http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(confirm(current,true))).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("COMPANY_VERIFICATION_REQUIRED")));
      database.update("UPDATE companies SET verification_status='REJECTED' WHERE id=?",company);
      http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(confirm(current,true))).andExpect(status().isConflict());
      http.perform(get(JOBS+"/"+job+"/unknown").header("Authorization",owner)).andExpect(status().isForbidden());

    }
    @Test void pendingReplacementNeverLeaksAndRejectionWithdrawalPreserveApprovedContent() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var original=complete();original.put("title","Approved original");var pending=submit(owner,create(owner,original));String admin=admin(owner);
      decide(admin,pending,"APPROVED");long job=pending.path("id").asLong(),approved=pending.path("workingRevision").path("id").asLong();
      var base=inspect(owner,job);var draft=derive(owner,base,approved);
      original.put("title","Private replacement");original.put("deadline","2099-12-15T12:00:00+07:00");
      draft=save(owner,draft,original);
      publicTitle(job,"Approved original");
      var replacement=submit(owner,draft);publicTitle(job,"Approved original");
      assertThat(inspect(owner,job).path("approvedDeadline").asText()).isEqualTo("2099-12-01T05:00:00Z");
      decide(admin,replacement,"REJECTED");publicTitle(job,"Approved original");
      var second=derive(owner,inspect(owner,job),null);var secondPending=submit(owner,second);withdraw(owner,secondPending);
      publicTitle(job,"Approved original");var third=derive(owner,inspect(owner,job),null);
      var evidence=history(owner,job);assertThat(evidence.get(3).path("sourceRevisionId").asLong()).isEqualTo(evidence.get(2).path("id").asLong());
      assertThat(evidence.get(2).path("state").asText()).isEqualTo("WITHDRAWN");
      assertThat(evidence.get(2).path("withdrawnAt").asText()).isNotBlank();
      var thirdPending=submit(owner,third);decide(admin,thirdPending,"APPROVED");publicTitle(job,"Private replacement");
      assertThat(inspect(owner,job).path("approvedDeadline").asText()).isEqualTo("2099-12-15T05:00:00Z");
      assertThat(history(owner,job).get(0)).isEqualTo(evidence.get(0));assertThat(history(owner,job).get(1)).isEqualTo(evidence.get(1));
      http.perform(get("/api/admin/jobs/"+job+"/revisions").header("Authorization",admin)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(4));
    }
    @Test void approvingPendingExtensionAfterOldDeadlinePreservesExpiry() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var original=complete();var oldDeadline=clock.now.plusSeconds(30);original.put("deadline",oldDeadline.toString());original.put("title","Original");
      var p=submit(owner,create(owner,original));String admin=admin(owner);decide(admin,p,"APPROVED");long job=p.path("id").asLong();
      var draft=derive(owner,inspect(owner,job),null);original.put("deadline",oldDeadline.plusSeconds(120).toString());original.put("title","Extension");
      var replacement=submit(owner,save(owner,draft,original));publicTitle(job,"Original");clock.now=oldDeadline;
      http.perform(put("/api/admin/job-revisions/"+replacement.path("workingRevision").path("id").asLong()+"/decision")
        .header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(decision(replacement,"APPROVED",null)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED"));
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Extension"))
        .andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED")).andExpect(jsonPath("$.data.applicationsAvailable").value(false));
      var expired=inspect(owner,job);
      http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",expired.path("version").asLong())))).andExpect(status().isConflict());
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isEqualTo(1);
    }
    @Test void competingDerivationsCreateOnlyOneWorkingRevision() throws Exception {
      String owner=account(),recruit=account();long company=createdCompany(owner);recruiter(company,recruit);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var p=submit(recruit,create(recruit,complete()));long job=p.path("id").asLong();withdraw(owner,p);var current=inspect(owner,job);
      String route=JOBS+"/"+job+"/draft",body=mapper.writeValueAsString(Map.of("expectedJobVersion",current.path("version").asLong()));
      try(var connection=database.getDataSource().getConnection();var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
        connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
        var first=workers.submit(()->http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        var second=workers.submit(()->http.perform(post(route).header("Authorization",recruit).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        try {
          long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
          while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
            if(System.nanoTime()>until)throw new AssertionError("Derivations did not reach Company lock");Thread.sleep(10);
          }connection.commit();
        }finally{connection.rollback();}
        assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      }
      var d=inspect(owner,job);
      http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",d.path("version").asLong())))).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("WORKING_REVISION_EXISTS")));
      assertThat(history(owner,job).size()).isEqualTo(2);
      assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions WHERE job_id=? AND state IN ('DRAFT','PENDING_REVIEW')",Long.class,job)).isEqualTo(1);
    }
    @Test void sourcesHistoryAndPaginationRemainScopedToCurrentAccess() throws Exception {
      String owner=account(),other=account(),recruit=account();long company=createdCompany(owner);createdCompany(other);recruiter(company,recruit);
      var d=create(owner,Map.of());var foreign=create(other,Map.of());long job=d.path("id").asLong();
      http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("sourceRevisionId",foreign.path("workingRevision").path("id").asLong()))))
        .andExpect(status().isNotFound());
      for(String actor:java.util.List.of(other,recruit)) {
        http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",actor)).andExpect(status().isNotFound());
        http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isNotFound());
      }
      for(String query:java.util.List.of("?page=-1","?size=0","?size=101","?page=2147483647&size=100"))
        http.perform(get(JOBS+"/"+job+"/revisions"+query).header("Authorization",owner)).andExpect(status().isBadRequest());
      http.perform(get(JOBS+"/"+job+"/revisions?size=1").header("Authorization",owner)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].revisionNumber").value(1));
      http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    }
    void publicTitle(long job,String title)throws Exception {
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value(title));
    }
    com.fasterxml.jackson.databind.JsonNode history(String owner,long job)throws Exception {
      return mapper.readTree(http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
    }
    com.fasterxml.jackson.databind.JsonNode derive(String owner,com.fasterxml.jackson.databind.JsonNode d,Long source)throws Exception {
      var body=new java.util.LinkedHashMap<String,Object>();body.put("expectedJobVersion",d.path("version").asLong());if(source!=null)body.put("sourceRevisionId",source);
      return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    com.fasterxml.jackson.databind.JsonNode save(String owner,com.fasterxml.jackson.databind.JsonNode d,Map<String,Object> c)throws Exception {
      return mapper.readTree(http.perform(put(JOBS+"/"+d.path("id").asLong()+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(saveBody(d,c))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void decide(String admin,com.fasterxml.jackson.databind.JsonNode pending,String outcome)throws Exception {
      http.perform(put("/api/admin/job-revisions/"+pending.path("workingRevision").path("id").asLong()+"/decision").header("Authorization",admin)
        .contentType(MediaType.APPLICATION_JSON).content(decision(pending,outcome,outcome.equals("REJECTED")?"Please correct the benefits":null))).andExpect(status().isOk());
    }
    void withdraw(String owner,com.fasterxml.jackson.databind.JsonNode pending)throws Exception {
      http.perform(post(JOBS+"/"+pending.path("id").asLong()+"/revisions/"+pending.path("workingRevision").path("id").asLong()+"/withdraw")
        .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(pending,true))).andExpect(status().isOk());
    }
    com.fasterxml.jackson.databind.JsonNode inspect(String owner,long job)throws Exception {
      return mapper.readTree(http.perform(get(JOBS+"/"+job).header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    com.fasterxml.jackson.databind.JsonNode submit(String owner,com.fasterxml.jackson.databind.JsonNode d)throws Exception {
      return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit")
        .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String decision(com.fasterxml.jackson.databind.JsonNode pending,String outcome,String reason)throws Exception {
        var body=new java.util.LinkedHashMap<String,Object>(); body.put("expectedJobVersion",pending.path("version").asLong());
        body.put("expectedRevisionVersion",pending.path("workingRevision").path("version").asLong());body.put("outcome",outcome);if(reason!=null)body.put("reason",reason);
        return mapper.writeValueAsString(body);
    }
    String admin(String token)throws Exception {
        long id=userId(token);database.update("UPDATE users SET role='ADMIN' WHERE id=?",id);
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
