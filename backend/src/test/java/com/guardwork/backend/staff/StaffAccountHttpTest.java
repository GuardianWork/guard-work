package com.guardwork.backend.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.JwtService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class StaffAccountHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  @Autowired PasswordEncoder passwords;
  @Autowired ObjectMapper json;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.guardwork.backend.staff.service.AccountEnforcementService enforcement;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.guardwork.backend.auth.repository.RefreshTokenRepository refresh;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.guardwork.backend.audit.service.AuditService audit;
  MockMvc http;
  record Account(long id, String username, String token) {}
  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
  Account account(String role) {
    String name = "acc_" + UUID.randomUUID().toString().replace("-", "");
    long id = db.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role,status) VALUES ('An','Nguyen',?,?,?,?,'ACTIVE') RETURNING id", Long.class,
        name, name + "@test.invalid", passwords.encode("GuardWork123!"), role);
    return new Account(id, name, "Bearer " + jwt.generateAccessToken(id,name,name+"@test.invalid",role));
  }
  void action(Account staff, long id, String action, String body, int status) throws Exception {
    http.perform(post("/api/staff/accounts/users/"+id+"/"+action)
        .header("Authorization",staff.token()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status));
  }
  @Test void privateFieldsAreExcludedAndSearchIsLiteral() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    http.perform(get("/api/staff/accounts/users/"+user.id()).header("Authorization",staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").exists())
        .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.sessionVersion").doesNotExist())
        .andExpect(jsonPath("$.data.canBan").value(true));
    http.perform(get("/api/staff/accounts/users").param("q",user.username()).header("Authorization",staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    http.perform(get("/api/staff/accounts/users").param("q","%_").header("Authorization",staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    http.perform(get("/api/staff/accounts/users").header("Authorization",user.token())).andExpect(status().isForbidden());
    http.perform(get("/api/staff/accounts/users")).andExpect(status().isUnauthorized());
  }
  @Test void banAndUnbanInvalidateOldAccessAndRefreshTokens() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    var login=json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("usernameOrEmail",user.username(),"password","GuardWork123!"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",200);
    http.perform(get("/api/auth/me").header("Authorization",user.token())).andExpect(status().isUnauthorized());
    action(staff,user.id(),"unban","{\"reason\":\"Appeal accepted\"}",200);
    http.perform(get("/api/auth/me").header("Authorization",user.token())).andExpect(status().isUnauthorized());
    http.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("refreshToken",login.path("refreshToken").asText())))).andExpect(status().isUnauthorized());
    var fresh=json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("usernameOrEmail",user.username(),"password","GuardWork123!"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    http.perform(get("/api/auth/me").header("Authorization","Bearer "+fresh.path("accessToken").asText())).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT session_version FROM users WHERE id=?",Long.class,user.id())).isEqualTo(2);
    assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type='USER' AND target_id=?",Long.class,""+user.id())).isEqualTo(2);
  }
  @Test void expiryRestoresAccessWithoutRevivingOldSessionsAndReadsDoNotWrite() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    action(staff,user.id(),"ban","{\"reason\":\"Temporary violation\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}",200);
    db.update("UPDATE account_bans SET started_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE user_id=?",user.id());
    http.perform(get("/api/staff/accounts/users/"+user.id()).header("Authorization",staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.activeBan").isEmpty());
    assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,user.id())).isEqualTo("BANNED");
    var result=json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("usernameOrEmail",user.username(),"password","GuardWork123!"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    http.perform(get("/api/auth/me").header("Authorization","Bearer "+result.path("accessToken").asText())).andExpect(status().isOk());
    http.perform(get("/api/auth/me").header("Authorization",user.token())).andExpect(status().isUnauthorized());
    assertThat(db.queryForObject("SELECT end_reason FROM account_bans WHERE user_id=?",String.class,user.id())).isEqualTo("SYSTEM_EXPIRY");
  }
  @Test void auditFailureRollsBackStateHistoryAndSessionRevocation() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    org.mockito.Mockito.doThrow(new IllegalStateException("Simulated audit failure")).when(audit).recordLog(
        org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class));
    try { action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",500); }
    finally { org.mockito.Mockito.reset(audit); }
    assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,user.id())).isEqualTo("ACTIVE");
    assertThat(db.queryForObject("SELECT session_version FROM users WHERE id=?",Long.class,user.id())).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM account_bans WHERE user_id=?",Long.class,user.id())).isZero();
    http.perform(get("/api/auth/me").header("Authorization",user.token())).andExpect(status().isOk());
  }
  @Test void revocationFailureRollsBackBanAndEnforcementFailureReturns503() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
        .when(refresh).revokeAllForUser(org.mockito.ArgumentMatchers.eq(user.id()),org.mockito.ArgumentMatchers.any());
    try { action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",503); }
    finally { org.mockito.Mockito.reset(refresh); }
    assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,user.id())).isEqualTo("ACTIVE");
    assertThat(db.queryForObject("SELECT session_version FROM users WHERE id=?",Long.class,user.id())).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM account_bans WHERE user_id=?",Long.class,user.id())).isZero();
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
        .when(enforcement).authenticate(user.id(),0);
    try { http.perform(get("/api/auth/me").header("Authorization",user.token())).andExpect(status().isServiceUnavailable()); }
    finally { org.mockito.Mockito.reset(enforcement); }
  }
  @Test void unrelatedInactiveStateCannotBeRestoredByUnban() throws Exception {
    var staff=account("STAFF");var user=account("USER");
    action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",200);
    db.update("UPDATE users SET status='DELETED' WHERE id=?",user.id());
    http.perform(get("/api/staff/accounts/users/"+user.id()).header("Authorization",staff.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.canUnban").value(false));
    action(staff,user.id(),"unban","{\"reason\":\"Appeal accepted\"}",409);
    assertThat(db.queryForObject("SELECT status FROM users WHERE id=?",String.class,user.id())).isEqualTo("DELETED");
  }
  @Test void competingBansProduceOneSuccessAndOneConflict() throws Exception {
    var first=account("STAFF"); var second=account("STAFF"); var user=account("USER");
    var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var start=new java.util.concurrent.CountDownLatch(1);
      var tasks=new ArrayList<java.util.concurrent.Future<Integer>>();
      for(var staff:List.of(first,second)) tasks.add(pool.submit(() -> {
        start.await();
        return http.perform(post("/api/staff/accounts/users/"+user.id()+"/ban").header("Authorization",staff.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Concurrent violation\"}"))
            .andReturn().getResponse().getStatus();
      }));
      start.countDown();
      var statuses=new ArrayList<Integer>();
      for(var task:tasks) statuses.add(task.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder(200,409);
      assertThat(db.queryForObject("SELECT count(*) FROM account_bans WHERE user_id=?",Long.class,user.id())).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }
  @Test void invalidAndRepeatedTransitionsAreRefused() throws Exception {
    var staff=account("STAFF"); var user=account("USER");
    action(staff,staff.id(),"ban","{\"reason\":\"Self ban\"}",403);
    action(staff,user.id(),"ban","{\"reason\":\" \"}",400);
    action(staff,user.id(),"ban","{\"reason\":\"Violation\",\"role\":\"STAFF\"}",400);
    action(staff,user.id(),"ban","{\"reason\":\"Violation\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",400);
    action(staff,user.id(),"unban","{\"reason\":\"Appeal\"}",409);
    action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",200);
    action(staff,user.id(),"ban","{\"reason\":\"Violation\"}",409);
    action(staff,user.id(),"unban","{\"reason\":\"\"}",400);
  }
}
