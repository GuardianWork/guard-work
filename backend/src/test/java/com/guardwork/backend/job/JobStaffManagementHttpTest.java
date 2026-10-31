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
@Import(JobStaffManagementHttpTest.TimeConfig.class)
class JobStaffManagementHttpTest {
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
    @Test void hidingRemovesPublicContentAndRetainsAttributedReadOnlyHistory() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      var pending=submit(owner,create(owner,complete()));String staff=staff(owner);decide(staff,pending,"APPROVED");
      long job=pending.path("id").asLong();var published=inspect(owner,job);
      http.perform(get("/api/jobs/"+job)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
      var before=history(owner,job);
      http.perform(put("/api/staff/jobs/"+job+"/hide").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",published.path("version").asLong(),"reason","  Vi phạm quy định  "))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value("SUSPENDED"))
        .andExpect(jsonPath("$.data.reason").value("Vi phạm quy định")).andExpect(jsonPath("$.data.actorUserId").value(userId(staff)))
        .andExpect(jsonPath("$.data.jobVersion").value(published.path("version").asLong()+1));
      http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.data").isEmpty());
      http.perform(get("/api/jobs/"+job).header("Authorization",owner)).andExpect(status().isNotFound());
      assertThat(history(owner,job)).isEqualTo(before);
      var hidden=inspect(owner,job);assertThat(hidden.path("status").asText()).isEqualTo("SUSPENDED");
      assertThat(hidden.path("availableActions").toString()).doesNotContain("CREATE_DRAFT","RESUME","CLOSE");
      http.perform(get(JOBS+"?status=SUSPENDED").header("Authorization",owner)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].id").value(job));
      long version=hidden.path("version").asLong();
      http.perform(get("/api/staff/jobs/"+job+"/history?size=1").header("Authorization",staff))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].eventType").value("JOB_SUSPENDED"))
        .andExpect(jsonPath("$.data.content[0].previousStatus").value("PUBLISHED"))
        .andExpect(jsonPath("$.data.content[0].status").value("SUSPENDED"))
        .andExpect(jsonPath("$.data.content[0].reason").value("Vi phạm quy định"))
        .andExpect(jsonPath("$.data.content[0].facts").doesNotExist());
      assertThat(inspect(owner,job).path("version").asLong()).isEqualTo(version);
      assertThat(database.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type='JOB' AND target_id=? AND action='JOB_SUSPENDED'",Long.class,Long.toString(job))).isEqualTo(1);
    }

    @Test void historyAndHidingRequireStaffAndRejectInvalidInputsWithoutMutation() throws Exception {
      String owner=account();long company=createdCompany(owner);var draft=create(owner,Map.of());long job=draft.path("id").asLong();
      String route="/api/staff/jobs/"+job;
      http.perform(get(route+"/history")).andExpect(status().isUnauthorized());
      http.perform(put(route+"/hide").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
      http.perform(get(route+"/history").header("Authorization",owner)).andExpect(status().isForbidden());
      http.perform(put(route+"/hide").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
      String staff=staff(account());
      for(String body:List.of("{}","{\"expectedJobVersion\":0}","{\"expectedJobVersion\":-1,\"reason\":\"x\"}",
          mapper.writeValueAsString(Map.of("expectedJobVersion",0,"reason","  ")),
          mapper.writeValueAsString(Map.of("expectedJobVersion",0,"reason","x".repeat(1001)))))
        http.perform(put(route+"/hide").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
      http.perform(put(route+"/hide").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",0,"reason","x")))).andExpect(status().isConflict());
      for(String query:List.of("?page=-1","?size=0","?size=101","?page=2147483647&size=100"))
        http.perform(get(route+"/history"+query).header("Authorization",staff)).andExpect(status().isBadRequest());
      http.perform(get("/api/staff/jobs/9223372036854775807/history").header("Authorization",staff)).andExpect(status().isNotFound());
      assertThat(inspect(owner,job).path("status").asText()).isEqualTo("DRAFT");
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_SUSPENDED'",Long.class,job)).isZero();
    }

    @Autowired com.guardwork.backend.job.service.StaffJobManagementService management;
    @Autowired com.guardwork.backend.job.service.JobExpiryService expiry;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.guardwork.backend.job.repository.JobAuditEventRepository eventRepository;

    @Test void currentStaffAuthorityIsRequiredEvenThroughDirectServiceCalls() throws Exception {
      String user=account();long actor=userId(user);
      org.assertj.core.api.Assertions.assertThatThrownBy(()->management.history(actor,1L,0,20))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("403");
      String staff=staff(user);database.update("UPDATE users SET status='SUSPENDED' WHERE id=?",actor);
      org.assertj.core.api.Assertions.assertThatThrownBy(()->management.hide(actor,1L,
          new com.guardwork.backend.job.service.StaffJobManagementService.HideRequest(0L,"x"),null,null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("40304");
      http.perform(get("/api/staff/jobs/1/history").header("Authorization",staff)).andExpect(status().isForbidden());
      database.update("UPDATE users SET status='ACTIVE',role='USER' WHERE id=?",actor);
      http.perform(get("/api/staff/jobs/1/history").header("Authorization",staff)).andExpect(status().isForbidden());
    }

    @Test void invalidStatesCompanyEligibilityAndExactDeadlinePreventHiding() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      String staff=staff(account());var draft=create(owner,complete());long job=draft.path("id").asLong();
      hide(staff,draft,"x").andExpect(status().isConflict());decide(staff,submit(owner,draft),"APPROVED");
      var published=inspect(owner,job);
      hide(staff,draft,"x").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(Matchers.containsString("CONCURRENT_MODIFICATION")));
      database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?",company);
      hide(staff,published,"x").andExpect(status().isConflict());
      database.update("UPDATE companies SET verification_status='VERIFIED',is_banned=true WHERE id=?",company);
      hide(staff,published,"x").andExpect(status().isConflict());
      database.update("UPDATE companies SET is_banned=false WHERE id=?",company);
      http.perform(post(JOBS+"/"+job+"/pause").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",published.path("version").asLong())))).andExpect(status().isOk());
      hide(staff,inspect(owner,job),"x").andExpect(status().isConflict());
      database.update("UPDATE job_postings SET status='PUBLISHED' WHERE id=?",job);
      var current=inspect(owner,job);clock.now=Instant.parse(current.path("approvedDeadline").asText());
      hide(staff,current,"x").andExpect(status().isConflict());
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_SUSPENDED'",Long.class,job)).isZero();
      // History must not persist the effective expiry of a still-PUBLISHED row.
      http.perform(get("/api/staff/jobs/"+job+"/history").header("Authorization",staff)).andExpect(status().isOk());
      assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("PUBLISHED");
      expiry.expireDue();hide(staff,inspect(owner,job),"x").andExpect(status().isConflict());
      http.perform(get("/api/staff/jobs/"+job+"/history?size=1").header("Authorization",staff))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].eventType").value("JOB_EXPIRED"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").isEmpty());
      http.perform(put("/api/staff/jobs/9223372036854775807/hide").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedJobVersion\":0,\"reason\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test void suspendedDraftAndPendingReviewCannotRepublishButReviewsRemainDecidable() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);String staff=staff(account());
      for(String outcome:List.of("APPROVED","REJECTED","WITHDRAWN","DRAFT")) {
        clock.now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var first=submit(owner,create(owner,complete()));decide(staff,first,"APPROVED");long job=first.path("id").asLong();
        var replacement=derive(owner,inspect(owner,job),null);
        if(!outcome.equals("DRAFT")) replacement=submit(owner,replacement);
        hide(staff,replacement,"x").andExpect(status().isOk());var hidden=inspect(owner,job);
        assertThat(hidden.path("availableActions").toString()).doesNotContain("SUBMIT","SAVE_DRAFT","CREATE_DRAFT");
        if(outcome.equals("WITHDRAWN")) withdraw(owner,hidden);
        else if(outcome.equals("DRAFT")) {
          http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(saveBody(hidden,complete()))).andExpect(status().isConflict());
          http.perform(post(JOBS+"/"+job+"/revisions/"+hidden.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(hidden,true))).andExpect(status().isConflict());
          http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.submissionEligible").value(false));
        } else decide(staff,hidden,outcome);
        var after=inspect(owner,job);assertThat(after.path("status").asText()).isEqualTo("SUSPENDED");
        for(String action:List.of("pause","resume","close"))
          http.perform(post(JOBS+"/"+job+"/"+action).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("expectedJobVersion",after.path("version").asLong())))).andExpect(status().isConflict());
        http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
          .content(mapper.writeValueAsString(Map.of("expectedJobVersion",after.path("version").asLong())))).andExpect(status().isConflict());
        http.perform(get("/api/jobs/"+job)).andExpect(status().isNotFound());
        clock.now=Instant.parse(after.path("approvedDeadline").asText());expiry.expireDue();
        assertThat(inspect(owner,job).path("status").asText()).isEqualTo("SUSPENDED");
      }
    }

    @Test void closedJobCannotBeHiddenAndNewRoutesAreDocumented() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      String staff=staff(account());var pending=submit(owner,create(owner,complete()));decide(staff,pending,"APPROVED");
      long job=pending.path("id").asLong();var current=inspect(owner,job);
      http.perform(post(JOBS+"/"+job+"/close").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",current.path("version").asLong())))).andExpect(status().isOk());
      hide(staff,inspect(owner,job),"x").andExpect(status().isConflict());
      var paths=mapper.readTree(http.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString()).path("paths");
      assertThat(paths.has("/api/staff/jobs/{jobId}/hide")).isTrue();
      assertThat(paths.has("/api/staff/jobs/{jobId}/history")).isTrue();
    }

    @Test void bothAuditFailuresRollBackSuspensionAndSuccessfulRetryRecordsOnce() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);String staff=staff(account());
      for(String table:List.of("job_audit_events","audit_logs")) {
        var pending=submit(owner,create(owner,complete()));decide(staff,pending,"APPROVED");long job=pending.path("id").asLong();var published=inspect(owner,job);
        database.execute("CREATE FUNCTION gw93_audit_failure() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure'; END; $$ LANGUAGE plpgsql");
        String condition=table.equals("job_audit_events")?"NEW.job_id="+job+" AND NEW.event_type='JOB_SUSPENDED'":"NEW.target_id='"+job+"' AND NEW.action='JOB_SUSPENDED'";
        database.execute("CREATE TRIGGER gw93_audit_failure BEFORE INSERT ON "+table+" FOR EACH ROW WHEN ("+condition+") EXECUTE FUNCTION gw93_audit_failure()");
        try {hide(staff,published,"x").andExpect(status().isInternalServerError());}
        finally {database.execute("DROP TRIGGER gw93_audit_failure ON "+table);database.execute("DROP FUNCTION gw93_audit_failure()");}
        assertThat(inspect(owner,job)).isEqualTo(published);
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_SUSPENDED'",Long.class,job)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type='JOB' AND target_id=? AND action='JOB_SUSPENDED'",Long.class,Long.toString(job))).isZero();
        hide(staff,published,"😀".repeat(1000)).andExpect(status().isOk());
        hide(staff,published,"x").andExpect(status().isConflict());
        hide(staff,inspect(owner,job),"x").andExpect(status().isConflict());
      }
    }

    @Test void historyIsDeterministicPrivateAndDistinguishesEmptyFromFailure() throws Exception {
      String owner=account();long company=createdCompany(owner);String staff=staff(account());
      long job=database.queryForObject("INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at) VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id",Long.class,company,userId(owner));
      String route="/api/staff/jobs/"+job+"/history";
      http.perform(get(route).header("Authorization",staff)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
      for(int i=0;i<3;i++) database.update("INSERT INTO job_audit_events(company_id,job_id,actor_user_id,event_type,created_at,facts) VALUES (?,?,?,'JOB_CREATED',?,?::jsonb)",
        company,job,userId(owner),java.sql.Timestamp.from(clock.now),"{\"reason\":\"Recorded reason\",\"privateContact\":\"secret\"}");
      var page=mapper.readTree(http.perform(get(route+"?size=2").header("Authorization",staff)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(3)).andReturn().getResponse().getContentAsString()).path("data");
      assertThat(page.path("content").get(0).path("id").asLong()).isGreaterThan(page.path("content").get(1).path("id").asLong());
      assertThat(page.toString()).doesNotContain("privateContact","secret");
      assertThat(page.path("content").get(0).path("status").isNull()).isTrue();
      http.perform(get(route+"?page=1&size=2").header("Authorization",staff)).andExpect(jsonPath("$.data.content.length()").value(1));
      org.mockito.Mockito.doThrow(new IllegalStateException("controlled read failure")).when(eventRepository)
        .findByJobId(org.mockito.ArgumentMatchers.eq(job),org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class));
      try {http.perform(get(route).header("Authorization",staff)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data").isEmpty());}
      finally {org.mockito.Mockito.reset(eventRepository);}
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=?",Long.class,job)).isEqualTo(3);
    }

    @Test void concurrentStaffHidesProduceExactlyOneSuspension() throws Exception {
      String owner=account();long company=createdCompany(owner);
      database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
      String firstStaff=staff(account()),secondStaff=staff(account());var pending=submit(owner,create(owner,complete()));decide(firstStaff,pending,"APPROVED");
      long job=pending.path("id").asLong();var published=inspect(owner,job);
      try(var connection=database.getDataSource().getConnection();var workers=Executors.newFixedThreadPool(2)) {
        connection.setAutoCommit(false);
        try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
        var first=workers.submit(()->hide(firstStaff,published,"first").andReturn().getResponse().getStatus());
        var second=workers.submit(()->hide(secondStaff,published,"second").andReturn().getResponse().getStatus());
        try {
          long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
          while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
            if(System.nanoTime()>until)throw new AssertionError("Hides did not reach Company lock");Thread.sleep(10);
          }
          connection.commit();
        } finally {connection.rollback();}
        assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
      }
      assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_SUSPENDED'",Long.class,job)).isEqualTo(1);
    }

    org.springframework.test.web.servlet.ResultActions hide(String staff,JsonNode job,String reason) throws Exception {
      return http.perform(put("/api/staff/jobs/"+job.path("id").asLong()+"/hide").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("expectedJobVersion",job.path("version").asLong(),"reason",reason))));
    }

    JsonNode history(String owner,long job)throws Exception {
      return mapper.readTree(http.perform(get(JOBS+"/"+job+"/revisions").header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
    }
    JsonNode derive(String owner,JsonNode d,Long source)throws Exception {
      var body=new LinkedHashMap<String,Object>();body.put("expectedJobVersion",d.path("version").asLong());if(source!=null)body.put("sourceRevisionId",source);
      return mapper.readTree(http.perform(post(JOBS+"/"+d.path("id").asLong()+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
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
        c.put("title", "Bảo vệ");
        c.put("description", "Dòng một\nDòng hai");
        c.put("location", "Hồ Chí Minh");
        c.put("employmentType", "FULL_TIME");c.put("workArrangement", "ONSITE");c.put("headcount",2);
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
        database.update("UPDATE users SET email_verified=TRUE WHERE email=?", email);
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
