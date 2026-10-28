package com.guardwork.backend.job;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"company.membership.notifications.worker-enabled=false", "jobs.expiry.worker-enabled=false"})
@ActiveProfiles("test")
@Import(JobRevisionHttpTest.TimeConfig.class)
class JobRevisionHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired MutableClock clock;
    MockMvc http;
    static final String JOBS = "/api/companies/me/jobs";
    @BeforeEach void setup() {
        clock.now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void rejectedContentDerivesDistinctDraftAndPreservesFeedback() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var fields=complete();var d=create(owner,fields);long job=d.path("id").asLong(),source=d.path("workingRevision").path("id").asLong();
      var pending=submit(owner,d);String staff=staff(owner);
      http.perform(put("/api/staff/job-revisions/"+source+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON)
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
      http.perform(get("/api/staff/jobs/"+job+"/revisions").header("Authorization",staff))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
      var evidence=history(owner,job).get(0);
      fields.put("title","Corrected benefits");var corrected=save(owner,derived,fields);var resubmitted=submit(owner,corrected);
      decide(staff,resubmitted,"APPROVED");
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Corrected benefits"));
      assertThat(history(owner,job).get(0)).isEqualTo(evidence);
      assertThat(history(owner,job).get(1).path("state").asText()).isEqualTo("APPROVED");

    }
    @Test void legacyEligibleMembersCanPrepareAllKnownJobRoutesBeforeCompanyVerification() throws Exception {
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
        .andExpect(jsonPath("$.message").value(Matchers.containsString("COMPANY_VERIFICATION_REQUIRED")));
      database.update("UPDATE companies SET verification_status='REJECTED' WHERE id=?",company);
      http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk());
      http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(confirm(current,true))).andExpect(status().isConflict());
      http.perform(get(JOBS+"/"+job+"/unknown").header("Authorization",owner)).andExpect(status().isNotFound());

    }
    @Test void pendingReplacementNeverLeaksAndRejectionWithdrawalPreserveApprovedContent() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var original=complete();original.put("title","Approved original");var pending=submit(owner,create(owner,original));String staff=staff(owner);
      decide(staff,pending,"APPROVED");long job=pending.path("id").asLong(),approved=pending.path("workingRevision").path("id").asLong();
      var base=inspect(owner,job);var draft=derive(owner,base,approved);
      original.put("title","Private replacement");original.put("deadline","2099-12-15T12:00:00+07:00");
      draft=save(owner,draft,original);
      publicTitle(job,"Approved original");
      var replacement=submit(owner,draft);publicTitle(job,"Approved original");
      assertThat(inspect(owner,job).path("approvedDeadline").asText()).isEqualTo("2099-12-01T05:00:00Z");
      decide(staff,replacement,"REJECTED");publicTitle(job,"Approved original");
      var second=derive(owner,inspect(owner,job),null);var secondPending=submit(owner,second);withdraw(owner,secondPending);
      publicTitle(job,"Approved original");var third=derive(owner,inspect(owner,job),null);
      var evidence=history(owner,job);assertThat(evidence.get(3).path("sourceRevisionId").asLong()).isEqualTo(evidence.get(2).path("id").asLong());
      assertThat(evidence.get(2).path("state").asText()).isEqualTo("WITHDRAWN");
      assertThat(evidence.get(2).path("withdrawnAt").asText()).isNotBlank();
      var thirdPending=submit(owner,third);decide(staff,thirdPending,"APPROVED");publicTitle(job,"Private replacement");
      assertThat(inspect(owner,job).path("approvedDeadline").asText()).isEqualTo("2099-12-15T05:00:00Z");
      assertThat(history(owner,job).get(0)).isEqualTo(evidence.get(0));assertThat(history(owner,job).get(1)).isEqualTo(evidence.get(1));
      http.perform(get("/api/staff/jobs/"+job+"/revisions").header("Authorization",staff)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(4));
    }
    @Test void approvingPendingExtensionAfterOldDeadlinePreservesExpiry() throws Exception {
      String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var original=complete();var oldDeadline=clock.now.plusSeconds(30);original.put("deadline",oldDeadline.toString());original.put("title","Original");
      var p=submit(owner,create(owner,original));String staff=staff(owner);decide(staff,p,"APPROVED");long job=p.path("id").asLong();
      var draft=derive(owner,inspect(owner,job),null);original.put("deadline",oldDeadline.plusSeconds(120).toString());original.put("title","Extension");
      var replacement=submit(owner,save(owner,draft,original));publicTitle(job,"Original");clock.now=oldDeadline;
      http.perform(put("/api/staff/job-revisions/"+replacement.path("workingRevision").path("id").asLong()+"/decision")
        .header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(replacement,"APPROVED",null)))
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
      try(var connection=database.getDataSource().getConnection();var workers=Executors.newFixedThreadPool(2)) {
        connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
        var first=workers.submit(()->http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        var second=workers.submit(()->http.perform(post(route).header("Authorization",recruit).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        try {
          long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
          while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
            if(System.nanoTime()>until)throw new AssertionError("Derivations did not reach Company lock");Thread.sleep(10);
          }connection.commit();
        }finally{connection.rollback();}
        assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      }
      var d=inspect(owner,job);
      http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",d.path("version").asLong())))).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("WORKING_REVISION_EXISTS")));
      assertThat(history(owner,job).size()).isEqualTo(2);
      assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions WHERE job_id=? AND state IN ('DRAFT','PENDING_REVIEW')",Long.class,job)).isEqualTo(1);
    }
    @Test void sourcesHistoryAndPaginationRemainScopedToCurrentAccess() throws Exception {
      String owner=account(),other=account(),recruit=account();long company=createdCompany(owner);createdCompany(other);recruiter(company,recruit);
      var d=create(owner,Map.of());var foreign=create(other,Map.of());long job=d.path("id").asLong();
      http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("sourceRevisionId",foreign.path("workingRevision").path("id").asLong()))))
        .andExpect(status().isNotFound());
      for(String actor:List.of(other,recruit)) {
        http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",actor)).andExpect(status().isNotFound());
        http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isNotFound());
      }
      for(String query:List.of("?page=-1","?size=0","?size=101","?page=2147483647&size=100"))
        http.perform(get(JOBS+"/"+job+"/revisions"+query).header("Authorization",owner)).andExpect(status().isBadRequest());
      http.perform(get(JOBS+"/"+job+"/revisions?size=1").header("Authorization",owner)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].revisionNumber").value(1));
      http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    }
    void publicTitle(long job,String title)throws Exception {
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value(title));
    }
    JsonNode history(String owner,long job)throws Exception {
      return mapper.readTree(http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
    }
    JsonNode derive(String owner,JsonNode d,Long source)throws Exception {
      var body=new LinkedHashMap<String,Object>();body.put("expectedJobVersion",d.path("version").asLong());if(source!=null)body.put("sourceRevisionId",source);
      return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode save(String owner,JsonNode d,Map<String,Object> c)throws Exception {
      return mapper.readTree(http.perform(put(JOBS+"/"+d.path("id").asLong()+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(saveBody(d,c))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void decide(String staff,JsonNode pending,String outcome)throws Exception {
      http.perform(put("/api/staff/job-revisions/"+pending.path("workingRevision").path("id").asLong()+"/decision").header("Authorization",staff)
        .contentType(MediaType.APPLICATION_JSON).content(decision(pending,outcome,outcome.equals("REJECTED")?"Please correct the benefits":null))).andExpect(status().isOk());
    }
    void withdraw(String owner,JsonNode pending)throws Exception {
      http.perform(post(JOBS+"/"+pending.path("id").asLong()+"/revisions/"+pending.path("workingRevision").path("id").asLong()+"/withdraw")
        .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(pending,true))).andExpect(status().isOk());
    }
    JsonNode inspect(String owner,long job)throws Exception {
      return mapper.readTree(http.perform(get(JOBS+"/"+job).header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode submit(String owner,JsonNode d)throws Exception {
      return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit")
        .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String decision(JsonNode pending,String outcome,String reason)throws Exception {
        var body=new LinkedHashMap<String,Object>(); body.put("expectedJobVersion",pending.path("version").asLong());
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
        var c=new LinkedHashMap<String,Object>();
        for(String f:List.of("title","responsibilities","requiredQualifications","preferredQualifications","experienceExpectations","benefits","location"))c.put(f,"Nội dung");
        c.put("description","Dòng một\nDòng hai"); c.put("applicationInstructions","Apply through GuardWork");
        c.put("employmentType","FULL_TIME");c.put("workArrangement","ONSITE");c.put("headcount",2);
        var questions=new ArrayList<Map<String,Object>>();
        for(String type:List.of("SHORT_TEXT","SINGLE_CHOICE","YES_NO","NUMERIC")) {
          var q=new LinkedHashMap<String,Object>();q.put("key",UUID.randomUUID().toString());q.put("type",type);
          q.put("prompt","Câu hỏi "+type);q.put("required",true);
          if(type.equals("SINGLE_CHOICE"))q.put("options",List.of("Một","Hai"));questions.add(q);
        }
        c.put("screeningQuestions",questions);
        c.put("deadline","2099-12-01T12:00:00+07:00");c.put("compensation",Map.of("disclosed",false));return c;
    }
    String confirm(JsonNode d,boolean confirmed)throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion",d.path("version").asLong(),"expectedRevisionVersion",d.path("workingRevision").path("version").asLong(),"confirmed",confirmed));
    }

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock jobTestClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }

    long recruiter(long company, String token) throws Exception {
        return database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id",
                Long.class, company, userId(token));
    }

    JsonNode create(String actor, Map<String, Object> content) throws Exception {
        return mapper.readTree(http.perform(post(JOBS).header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String saveBody(JsonNode detail, Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion", detail.path("version").asLong(),
                "expectedRevisionVersion", detail.path("workingRevision").path("version").asLong(), "content", content));
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString())).header("Authorization", token))
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
        database.update("UPDATE users SET recruiting_email_verification_required=FALSE WHERE email=?", email);
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
