package com.guardwork.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.guardwork.backend.auth.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class ActivityHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  MockMvc http;
  record Actor(long id, String token) {}
  @BeforeEach void setup() { http=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
  Actor actor(String role) {
    String name="activity_"+UUID.randomUUID();
    long id=db.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role,status) VALUES ('An','Nguyen',?,?,'unused',?,'ACTIVE') RETURNING id",
        Long.class,name,name+"@test.invalid",role);
    return new Actor(id,"Bearer "+jwt.generateAccessToken(id,name,name+"@test.invalid",role));
  }
  long event(Actor actor,String action,String type,String before,String after,String time) {
    return db.queryForObject("INSERT INTO audit_logs(admin_id,action,target_type,target_id,old_payload,new_payload,reason,ip_address,user_agent,created_at) VALUES (?,?,?,'123',?::jsonb,?::jsonb,'Recorded reason','127.0.0.1','private agent',?::timestamptz) RETURNING id",
        Long.class,actor.id(),action,type,before,after,time);
  }
  String url="/api/staff/me/activities?fromDate=2026-10-01&toDate=2026-10-30";

  @Test void isolatesActorAndProjectsOnlyHistoricalAllowedFields() throws Exception {
    var own=actor("STAFF"); var other=actor("STAFF");
    event(other,"ACCOUNT_BANNED","USER",null,"{\"status\":\"BANNED\"}","2026-10-15T00:00:00Z");
    event(own,"ACCOUNT_BANNED","USER","{\"status\":\"ACTIVE\",\"email\":\"private\"}","{\"status\":\"BANNED\",\"token\":\"secret\"}","2026-10-15T00:00:00Z");
    var result=http.perform(get(url+"&category=ACCOUNT_MANAGEMENT&targetType=USER&targetId=123").header("Authorization",own.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].changes[0].before").value("ACTIVE"))
        .andExpect(jsonPath("$.data.content[0].changes[0].after").value("BANNED"))
        .andExpect(jsonPath("$.data.content[0].reason").value("Recorded reason")).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("private","secret","127.0.0.1","adminId","old_payload");
  }

  @Test void usesVietnamInclusiveDatesAndStablePagination() throws Exception {
    var own=actor("STAFF");
    event(own,"ACCOUNT_BANNED","USER",null,null,"2026-09-30T16:59:59Z");
    long last=0;
    for(int i=0;i<21;i++) last=event(own,"ACCOUNT_BANNED","USER",null,null,"2026-09-30T17:00:00Z");
    event(own,"ACCOUNT_BANNED","USER",null,null,"2026-10-30T17:00:00Z");
    http.perform(get(url).header("Authorization",own.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(21)).andExpect(jsonPath("$.data.content.length()").value(20))
        .andExpect(jsonPath("$.data.content[0].id").value(last));
    http.perform(get(url+"&page=1").header("Authorization",own.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1)).andExpect(jsonPath("$.data.last").value(true));
    http.perform(get(url+"&page=20").header("Authorization",own.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0)).andExpect(jsonPath("$.data.totalElements").value(21));
  }

  @Test void preservesIncompleteLegacyEventsAndExcludesUnmappedActions() throws Exception {
    var own=actor("STAFF");
    event(own,"COMPANY_VERIFICATION","COMPANY",null,"{\"verificationStatus\":\"unknown private value\"}","2026-10-15T01:00:00Z");
    event(own,"JOB_REVISION_APPROVED","JOB_REVISION",null,"{\"outcome\":\"APPROVED\"}","2026-10-15T00:00:00Z");
    event(own,"LOGIN","USER",null,null,"2026-10-15T00:00:00Z");
    http.perform(get(url).header("Authorization",own.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].action").value("REVIEW_COMPANY"))
        .andExpect(jsonPath("$.data.content[0].changes[0].after").isEmpty())
        .andExpect(jsonPath("$.data.content[1].changes[0].before").isEmpty())
        .andExpect(jsonPath("$.data.content[1].changes[0].after").value("APPROVED"))
        .andExpect(jsonPath("$.data.content[1].reason").isEmpty());
  }

  @Test void rejectsUnsupportedDuplicateAndIncompatibleFilters() throws Exception {
    var own=actor("STAFF");
    for(String query:new String[]{"actorId=1","page=0&page=1","size=21","page=-1","fromDate=2026-02-30&toDate=2026-03-01",
        "fromDate=2026-10-01","targetId=123","category=JOB_MODERATION&targetType=USER","category=ACCOUNT_MANAGEMENT&action=APPROVE_COMPANY"}) {
      http.perform(get("/api/staff/me/activities?"+query).header("Authorization",own.token())).andExpect(status().isBadRequest());
    }
  }

  @Test void defaultRangeCoversExactlyThirtyVietnamDates() throws Exception {
    var own=actor("STAFF");
    var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
    var start=today.minusDays(29).atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    event(own,"ACCOUNT_BANNED","USER",null,null,start.minusSeconds(1).toString());
    event(own,"ACCOUNT_BANNED","USER",null,null,start.toString());
    http.perform(get("/api/staff/me/activities").header("Authorization",own.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test void filtersModerationByImmutablePostingIdentity() throws Exception {
    var own=actor("STAFF");
    long company=db.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url,verification_status) VALUES ('History Company',?,'retained','VERIFIED') RETURNING id",Long.class,UUID.randomUUID().toString());
    long job=db.queryForObject("INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at) VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id",Long.class,company,own.id());
    long revision=db.queryForObject("INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,created_at) VALUES (?,1,'{}'::jsonb,'DRAFT',?,now()) RETURNING id",Long.class,job,own.id());
    db.update("INSERT INTO audit_logs(admin_id,action,target_type,target_id,old_payload,new_payload,ip_address,created_at) VALUES (?,'JOB_REVISION_REJECTED','JOB_REVISION',?, '{\"reviewState\":\"PENDING_REVIEW\"}'::jsonb,'{\"reviewState\":\"REJECTED\"}'::jsonb,'127.0.0.1','2026-10-15T00:00:00Z')",own.id(),Long.toString(revision));
    http.perform(get(url+"&category=JOB_MODERATION&targetType=JOB_POSTING&targetId="+job).header("Authorization",own.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].targetId").value(Long.toString(job)))
        .andExpect(jsonPath("$.data.content[0].revisionId").value(Long.toString(revision)))
        .andExpect(jsonPath("$.data.content[0].changes[0].before").value("PENDING_REVIEW"));
  }

  @Test void requiresActiveStaffAuthentication() throws Exception {
    http.perform(get(url)).andExpect(status().isUnauthorized());
    var user=actor("USER");
    http.perform(get(url).header("Authorization",user.token())).andExpect(status().isForbidden());
    var staff=actor("STAFF");
    db.update("UPDATE users SET status='BANNED' WHERE id=?",staff.id());
    http.perform(get(url).header("Authorization",staff.token())).andExpect(status().isUnauthorized());
  }
}
