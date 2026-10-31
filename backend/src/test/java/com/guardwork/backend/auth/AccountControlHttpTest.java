package com.guardwork.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.context.annotation.Import(AccountEmailVerificationHttpTest.TimeConfig.class)
class AccountControlHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AccountEmailVerificationHttpTest.MutableClock clock;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.guardwork.backend.audit.service.AuditService audit;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;
    String code;
    static final String PASSWORD = "GuardWork123!";

    @BeforeEach void setup() {
        // Match PostgreSQL timestamp precision for exact-expiry boundary assertions.
        clock.now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(delivery.accountActionEmail(anyString(), anyString(), anyString())).thenAnswer(call -> { code=call.getArgument(1); return true; });
    }

    @Test void unverifiedIdentityIsSharedAndIdentifiersAreImmutable() throws Exception {
        JsonNode user=account(false);
        request(user,"/api/me/account",Map.of("firstName","An","lastName","Nguyễn","phoneNumber","0901234567"),true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("An Nguyễn"));
        http.perform(get("/api/candidates/profile").header("Authorization",auth(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("An Nguyễn"));
        request(user,"/api/candidates/profile",Map.of("firstName","Bình","lastName","Trần","skills",List.of()),true)
                .andExpect(status().isOk());
        http.perform(get("/api/me/account").header("Authorization",auth(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Bình Trần"));
        request(user,"/api/me/account",Map.of("firstName","An","lastName","Nguyễn","email","different@example.vn"),true)
                .andExpect(status().isBadRequest());
        request(user,"/api/candidates/profile",Map.of("firstName","An","lastName","Nguyễn","fullName","Old"),true)
                .andExpect(status().isBadRequest());
    }

    @Test void passwordRequiresVerificationAndInvalidatesAccessAndRefresh() throws Exception {
        JsonNode user=account(false);
        var body=Map.of("currentPassword",PASSWORD,"newPassword","NewPassword123!");
        request(user,"/api/auth/change-password",body,true).andExpect(status().isForbidden());
        db.update("UPDATE users SET email_verified=true WHERE id=?",id(user));
        request(user,"/api/auth/change-password",body,true).andExpect(status().isOk());
        http.perform(get("/api/auth/me").header("Authorization",auth(user))).andExpect(status().isUnauthorized());
        request(null,"/api/auth/refresh",Map.of("refreshToken",user.path("refreshToken").asText()),false)
                .andExpect(status().isUnauthorized());
    }

    @Test void deactivateAndRecoverPreservesVisibilityAndNeverRevivesSessions() throws Exception {
        JsonNode user=account(true);
        http.perform(get("/api/candidates/profile").header("Authorization",auth(user))).andExpect(status().isOk());
        close(user,"deactivation");
        assertThat(db.queryForObject("SELECT is_public FROM candidate_profiles WHERE user_id=?",Boolean.class,id(user))).isFalse();
        http.perform(get("/api/me/account").header("Authorization",auth(user))).andExpect(status().isUnauthorized());
        request(null,"/api/auth/login",Map.of("usernameOrEmail",username(user),"password",PASSWORD),false)
                .andExpect(status().isForbidden());
        recover(user,"REACTIVATE");
        assertThat(db.queryForObject("SELECT is_public FROM candidate_profiles WHERE user_id=?",Boolean.class,id(user))).isTrue();
        http.perform(get("/api/auth/me").header("Authorization",auth(user))).andExpect(status().isUnauthorized());
        request(null,"/api/auth/login",Map.of("usernameOrEmail",username(user),"password",PASSWORD),false).andExpect(status().isOk());
    }

    @Test void deletionCanBeCancelledButNeverErasesData() throws Exception {
        JsonNode user=account(true);
        close(user,"deletion");
        assertThat(db.queryForObject("SELECT count(*) FROM account_deletion_requests WHERE user_id=? AND cancelled_at IS NULL",Integer.class,id(user))).isEqualTo(1);
        recover(user,"CANCEL_DELETION");
        assertThat(db.queryForObject("SELECT count(*) FROM account_deletion_requests WHERE user_id=? AND cancelled_at IS NOT NULL",Integer.class,id(user))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT self_service_state FROM users WHERE id=?",String.class,id(user))).isEqualTo("ACTIVE");
    }

    @Test void proofsAreBoundedAndActionBoundAndWrongPasswordDoesNotSendMail() throws Exception {
        JsonNode user=account(true);
        request(user,"/api/me/account/deactivation/request-code",Map.of("currentPassword","wrong"),false).andExpect(status().isBadRequest());
        verifyNoInteractions(delivery);
        request(user,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        String correct=code;
        request(user,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isTooManyRequests());
        request(user,"/api/me/account/deletion/confirm",confirmation(correct),false).andExpect(status().isBadRequest());
        for(int i=0;i<5;i++) request(user,"/api/me/account/deactivation/confirm",confirmation("bad"),false).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT failed_attempts FROM account_action_proofs WHERE user_id=? AND purpose='DEACTIVATE'",Integer.class,id(user))).isEqualTo(5);
        request(user,"/api/me/account/deactivation/confirm",confirmation(correct),false).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT self_service_state FROM users WHERE id=?",String.class,id(user))).isEqualTo("ACTIVE");
    }

    @Test void independentBanPreventsRecoveryAndUnbanDoesNotRestoreVoluntaryAccess() throws Exception {
        JsonNode user=account(true);
        close(user,"deactivation");
        db.update("UPDATE users SET status='BANNED' WHERE id=?",id(user));
        request(null,"/api/auth/account-recovery/request-code",recovery(user,"REACTIVATE",null),false).andExpect(status().isForbidden());
        db.update("UPDATE users SET status='ACTIVE' WHERE id=?",id(user));
        request(null,"/api/auth/login",Map.of("usernameOrEmail",username(user),"password",PASSWORD),false).andExpect(status().isForbidden());
        recover(user,"REACTIVATE");
    }

    @Test void staffCannotUseAccountOrRecoveryAndClosureNeedsVerifiedEmail() throws Exception {
        JsonNode user=account(false);
        request(user,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isForbidden());
        db.update("UPDATE users SET role='STAFF' WHERE id=?",id(user));
        http.perform(get("/api/me/account").header("Authorization",auth(user))).andExpect(status().isForbidden());
        request(null,"/api/auth/account-recovery/request-code",recovery(user,"REACTIVATE",null),false).andExpect(status().isForbidden());
    }

    @Test void exactExpiryResendAndFailedDeliveryNeverChangeState() throws Exception {
        JsonNode u=account(true);
        request(u,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        String old=code;
        clock.now=clock.now.plusSeconds(600);
        request(u,"/api/me/account/deactivation/confirm",confirmation(old),false).andExpect(status().isBadRequest());
        doAnswer(call -> {code=call.getArgument(1);return false;}).when(delivery).accountActionEmail(anyString(),anyString(),anyString());
        request(u,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryAccepted").value(false));
        assertThat(db.queryForObject("SELECT self_service_state FROM users WHERE id=?",String.class,id(u))).isEqualTo("ACTIVE");
        request(u,"/api/me/account/deactivation/confirm",confirmation(old),false).andExpect(status().isBadRequest());
        clock.now=clock.now.plusSeconds(60);
        doAnswer(call -> {code=call.getArgument(1);return true;}).when(delivery).accountActionEmail(anyString(),anyString(),anyString());
        request(u,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        request(u,"/api/me/account/deactivation/confirm",confirmation(code),false).andExpect(status().isOk());
    }

    @Test void closureRechecksMembershipAndOwnerAndAllowsNormalAccountEdits() throws Exception {
        JsonNode owner=account(true);JsonNode member=account(true);
        request(member,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        String prior=code;
        long company=createCompany(owner);
        db.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now())",company,id(member));
        request(member,"/api/me/account/deactivation/confirm",confirmation(prior),false).andExpect(status().isConflict());
        request(owner,"/api/me/account/deletion/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isConflict());
        for(String role:List.of("RECRUITER","ADMIN")) {
            db.update("UPDATE company_memberships SET role=? WHERE user_id=?",role,id(member));
            request(member,"/api/me/account",Map.of("firstName","Company","lastName","Member"),true).andExpect(status().isOk());
            request(member,"/api/me/account/deletion/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isConflict());
        }
    }

    @Test void concurrentDeletionConfirmationsHaveOneWinnerAndOneRetainedRequest() throws Exception {
        JsonNode u=account(true);
        request(u,"/api/me/account/deletion/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        String proof=code;
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> action=() -> {start.await();return request(u,"/api/me/account/deletion/confirm",confirmation(proof),false).andReturn().getResponse().getStatus();};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            var outcomes=List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(n -> n==200).count()).isEqualTo(1);
            assertThat(outcomes).allMatch(n -> n==200 || n==401 || n==403 || n==409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM account_deletion_requests WHERE user_id=?",Integer.class,id(u))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE admin_id=? AND action='ACCOUNT_DELETE'",Integer.class,id(u))).isEqualTo(1);
    }

    @Test void auditFailureRollsBackClosureVisibilityAndSessionInvalidation() throws Exception {
        JsonNode u=account(true);
        http.perform(get("/api/candidates/profile").header("Authorization",auth(u))).andExpect(status().isOk());
        request(u,"/api/me/account/deactivation/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        String proof=code;
        doThrow(new IllegalStateException("test audit unavailable")).when(audit).recordLog(eq(id(u)),eq("ACCOUNT_DEACTIVATE"),anyString(),anyString(),any(),any(),any(),any(),any());
        request(u,"/api/me/account/deactivation/confirm",confirmation(proof),false).andExpect(status().isInternalServerError());
        assertThat(db.queryForObject("SELECT self_service_state FROM users WHERE id=?",String.class,id(u))).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("SELECT session_version FROM users WHERE id=?",Long.class,id(u))).isZero();
        assertThat(db.queryForObject("SELECT is_public FROM candidate_profiles WHERE user_id=?",Boolean.class,id(u))).isTrue();
        http.perform(get("/api/me/account").header("Authorization",auth(u))).andExpect(status().isOk());
    }

    @Test void recoveryRequiresCorrectActionAccountAndPortalAndDoesNotResurrectPrivateProfile() throws Exception {
        JsonNode u=account(true);JsonNode other=account(true);
        request(u,"/api/candidates/profile",Map.of("firstName","Private","lastName","User","isPublic",false),true).andExpect(status().isOk());
        close(u,"deactivation");
        request(null,"/api/auth/account-recovery/request-code",recovery(u,"CANCEL_DELETION",null),false).andExpect(status().isConflict());
        var wrongPassword=recovery(u,"REACTIVATE",null);wrongPassword.put("password","wrong");
        request(null,"/api/auth/account-recovery/request-code",wrongPassword,false).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/account-recovery/request-code").header("X-Guardian-Portal","staff").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(recovery(u,"REACTIVATE",null)))).andExpect(status().isForbidden());
        request(null,"/api/auth/account-recovery/request-code",recovery(u,"REACTIVATE",null),false).andExpect(status().isOk());
        request(null,"/api/auth/account-recovery/confirm",recovery(other,"REACTIVATE",code),false).andExpect(status().isConflict());
        request(null,"/api/auth/account-recovery/confirm",recovery(u,"REACTIVATE",code),false).andExpect(status().isOk());
        request(null,"/api/auth/account-recovery/confirm",recovery(u,"REACTIVATE",code),false).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT is_public FROM candidate_profiles WHERE user_id=?",Boolean.class,id(u))).isFalse();
    }

    long createCompany(JsonNode owner) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s -> {
            long company=db.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url,owner_user_id) VALUES ('Test',?,'test',?) RETURNING id",Long.class,UUID.randomUUID().toString(),id(owner));
            db.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())",company,id(owner));return company;
        });
    }

    @Test void nullAndMalformedBodiesAreInvalidAndDoNotChangeTheAccount() throws Exception {
        JsonNode u=account(true);
        for(String path:List.of("/api/me/account", "/api/me/account/deactivation/request-code", "/api/me/account/deactivation/confirm",
                "/api/auth/account-recovery/request-code", "/api/auth/account-recovery/confirm")) {
            for(String body:List.of("null", "{broken")) {
                var r=(path.equals("/api/me/account")?put(path):post(path)).header("Authorization",auth(u))
                        .contentType(MediaType.APPLICATION_JSON).content(body);
                http.perform(r).andExpect(status().isBadRequest());
            }
        }
        assertThat(db.queryForObject("SELECT self_service_state FROM users WHERE id=?",String.class,id(u))).isEqualTo("ACTIVE");
        verifyNoInteractions(delivery);
    }

    void close(JsonNode u,String action) throws Exception {
        request(u,"/api/me/account/"+action+"/request-code",Map.of("currentPassword",PASSWORD),false).andExpect(status().isOk());
        request(u,"/api/me/account/"+action+"/confirm",confirmation(code),false).andExpect(status().isOk());
    }
    void recover(JsonNode u,String action) throws Exception {
        request(null,"/api/auth/account-recovery/request-code",recovery(u,action,null),false).andExpect(status().isOk());
        request(null,"/api/auth/account-recovery/confirm",recovery(u,action,code),false).andExpect(status().isOk());
    }
    Map<String,Object> confirmation(String secret) { return Map.of("currentPassword",PASSWORD,"emailSecret",secret,"acknowledgeConsequences",true); }
    Map<String,Object> recovery(JsonNode u,String action,String secret) {
        var body=new HashMap<String,Object>(); body.put("usernameOrEmail",username(u)); body.put("password",PASSWORD); body.put("action",action);
        if(secret!=null) {body.put("emailSecret",secret);body.put("acknowledgeConsequences",true);} return body;
    }
    ResultActions request(JsonNode u,String path,Object body,boolean putMethod) throws Exception {
        var r=(putMethod?put(path):post(path)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(u!=null) r.header("Authorization",auth(u)); return http.perform(r);
    }
    JsonNode account(boolean verified) throws Exception {
        String name="ac_"+UUID.randomUUID().toString().replace("-","");
        request(null,"/api/auth/register",Map.of("firstName","Minh","lastName","Nguyễn","username",name,"email",name+"@example.vn","password",PASSWORD),false).andExpect(status().isCreated());
        JsonNode u=json.readTree(request(null,"/api/auth/login",Map.of("usernameOrEmail",name,"password",PASSWORD),false).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        db.update("UPDATE users SET email_verified=? WHERE id=?",verified,id(u)); return u;
    }
    long id(JsonNode u) {return u.path("user").path("id").asLong();}
    String username(JsonNode u) {return u.path("user").path("username").asText();}
    String auth(JsonNode u) {return "Bearer "+u.path("accessToken").asText();}
}
