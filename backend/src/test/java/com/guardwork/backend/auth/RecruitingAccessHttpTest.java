package com.guardwork.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.auth.service.AccountEmailVerificationService;
import com.guardwork.backend.auth.service.JwtService;
import com.guardwork.backend.job.dto.JobRequests;
import com.guardwork.backend.job.service.JobDraftService;
import com.guardwork.backend.job.service.JobSubmissionService;
import com.guardwork.backend.job.service.JobAssignmentService;
import com.guardwork.backend.job.service.JobPublicationService;
import com.guardwork.backend.membership.service.MembershipManagementService;
import com.guardwork.backend.invitation.service.OwnerInvitationService;
import java.util.List;
import com.guardwork.backend.onboarding.delivery.OnboardingDelivery;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class RecruitingAccessHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired JobDraftService drafts;
    @Autowired JobSubmissionService submissions;
    @Autowired JobAssignmentService assignments;
    @Autowired JobPublicationService publication;
    @Autowired MembershipManagementService membership;
    @Autowired OwnerInvitationService invitations;
    @Autowired AccountEmailVerificationService emailProof;
    @Autowired JdbcTemplate database;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtService tokens;
    @MockitoBean OnboardingDelivery delivery;
    MockMvc http;
    final Map<String, String> mailbox = new java.util.HashMap<>();

    @BeforeEach void setup() {
        org.mockito.Mockito.when(delivery.memberEmail(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> { mailbox.put(call.getArgument(0), call.getArgument(1)); return true; });
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void unverifiedNewOwnerCannotBypassEmailProofByCallingTheBusinessServiceDirectly() {
        var owner = account();
        company(owner, "PENDING");
        assertThatThrownBy(() -> drafts.create(owner.id(), new JobRequests.Create(Map.of("title", "Việc làm"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getReason()).startsWith("40335 EMAIL_VERIFICATION_REQUIRED");
                });
    }

    @Test void requestGuardDeniesPrivateRecruitingBeforePayloadParsingWithoutAWorkspaceHeader() throws Exception {
        var owner = account();
        company(owner, "PENDING");
        http.perform(post("/api/companies/me/jobs").header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(
                        "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
    }

    @Test void suspendedUserCannotReadCurrentAccountThroughTheBusinessService() {
        var user = account();
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", user.id());
        assertThatThrownBy(() -> auth.getCurrentUser(user.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test void newOwnerCannotReadEditOrManagePrivateRecruitingWithEitherWorkspaceChoice() throws Exception {
        var owner = account();
        company(owner, "PENDING");
        verifyEmail(owner);
        var draft = drafts.create(owner.id(), new JobRequests.Create(Map.of("title", "Việc làm")));
        database.update("UPDATE users SET email_verified=FALSE WHERE id=?", owner.id());
        for (boolean withHeader : new boolean[]{false, true}) {
            for (String path : List.of("/api/companies/me/jobs", "/api/companies/me/jobs/" + draft.id(),
                    "/api/companies/me/jobs/" + draft.id() + "/draft/preview", "/api/companies/me/members",
                    "/api/companies/me/invitations")) {
                var request = get(path).header("Authorization", owner.authorization()).header("X-Guardwork-Workspace", "candidate");
                if (withHeader) request.header("X-Guardian-Portal", "public");
                http.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(
                        "40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
            }
        }
        http.perform(put("/api/companies/me/jobs/" + draft.id() + "/draft").header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> drafts.inspect(owner.id(), draft.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> drafts.save(owner.id(), draft.id(), null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> membership.list(owner.id(), 0, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> invitations.list(owner.id(), null, 0, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> assignments.responsibility(owner.id(), draft.id(), draft.version(), false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void verifiedMembersCanDraftForPendingAndRejectedCompaniesButCannotSubmitForModeration() throws Exception {
        for (String state : List.of("PENDING", "REJECTED")) {
            var owner = account();
            company(owner, state);
            verifyEmail(owner);
            var draft = drafts.create(owner.id(), new JobRequests.Create(Map.of("title", "Việc làm")));
            http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", owner.authorization()))
                    .andExpect(status().isOk());
            assertThatThrownBy(() -> submissions.submit(owner.id(), draft.id(), draft.workingRevision().id(),
                    new JobSubmissionService.Confirmation(draft.version(), draft.workingRevision().version(), true)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(error.getReason()).startsWith("40982 COMPANY_VERIFICATION_REQUIRED");
                    });
            http.perform(get("/api/v1/candidates/profile").header("Authorization", owner.authorization()))
                    .andExpect(status().isOk());
        }
    }

    @Test void emailProofDoesNotGrantMembershipAssignmentCompanyAdminOrStaffAuthority() throws Exception {
        var outsider = account();
        verifyEmail(outsider);
        assertThatThrownBy(() -> drafts.create(outsider.id(), new JobRequests.Create(Map.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        http.perform(get("/api/staff/job-revisions").header("Authorization", outsider.authorization())
                .header("X-Guardian-Portal", "staff")).andExpect(status().isForbidden());
        var owner = account();
        long company = company(owner, "VERIFIED");
        verifyEmail(owner);
        var recruiter = account();
        var admin = account();
        verifyEmail(recruiter);
        verifyEmail(admin);
        member(recruiter, company, "RECRUITER");
        member(admin, company, "ADMIN");
        var draft = drafts.create(owner.id(), new JobRequests.Create(Map.of()));
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", recruiter.authorization()))
                .andExpect(status().isNotFound());
        http.perform(post("/api/companies/me/jobs").header("Authorization", admin.authorization())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Test void losingMembershipRevokesPrivateJobAccessButRetainsOwnHistoricalInbox() throws Exception {
        var owner = account();
        long company = company(owner, "VERIFIED");
        verifyEmail(owner);
        var recruiter = account();
        verifyEmail(recruiter);
        long period = member(recruiter, company, "RECRUITER");
        var draft = drafts.create(owner.id(), new JobRequests.Create(Map.of()));
        assignments.assign(owner.id(), draft.id(), period, draft.version(), true);
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", recruiter.authorization()))
                .andExpect(status().isOk());
        membership.remove(owner.id(), period);
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", recruiter.authorization()))
                .andExpect(status().isNotFound());
        database.update("UPDATE users SET email_verified=FALSE WHERE id=?", recruiter.id());
        String inbox = http.perform(get("/api/me/notifications").header("Authorization", recruiter.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[?(@.eventType == 'MEMBERSHIP_REMOVED')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long notification = json.readTree(inbox).path("data").path("content").get(0).path("id").asLong();
        http.perform(patch("/api/me/notifications/" + notification + "/read").header("Authorization", recruiter.authorization()))
                .andExpect(status().isOk());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", recruiter.authorization()))
                .andExpect(status().isOk());
    }

    @Test void everyMemberRequiresVerifiedEmailRegardlessOfHistoricalProfiles() throws Exception {
        var owner = account();
        long company = company(owner, "PENDING");
        database.update("UPDATE users SET email_verified=TRUE WHERE id=?", owner.id());
        var draft = drafts.create(owner.id(), new JobRequests.Create(Map.of()));
        database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,false,'Existing member')", owner.id());
        database.update("UPDATE users SET email_verified=FALSE WHERE id=?", owner.id());
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", owner.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("40335 EMAIL_VERIFICATION_REQUIRED: Verify your account email before recruiting"));
        assertThatThrownBy(() -> drafts.create(owner.id(), new JobRequests.Create(Map.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EMAIL_VERIFICATION_REQUIRED");
        http.perform(get("/api/me/notifications").header("Authorization", owner.authorization())).andExpect(status().isOk());
        http.perform(get("/api/v1/candidates/profile").header("Authorization", owner.authorization())).andExpect(status().isOk());
        verifyEmail(owner);
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", owner.authorization()))
                .andExpect(status().isOk());
        database.update("UPDATE companies SET is_banned=TRUE WHERE id=?", company);
        http.perform(get("/api/companies/me/jobs/" + draft.id()).header("Authorization", owner.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test void publishedDetailAndStaffModerationRemainIndependentOfPersonalRecruitingProof() throws Exception {
        var owner = account();
        company(owner, "VERIFIED");
        verifyEmail(owner);
        var staff = staffAccount();
        var content = new java.util.LinkedHashMap<String, Object>();
        content.put("title", "Nội dung");
        content.put("description", "Dòng một\nDòng hai");
        content.put("location", "Hồ Chí Minh");
        content.put("employmentType", "FULL_TIME");
        content.put("workArrangement", "ONSITE");
        content.put("headcount", 2);
        content.put("deadline", "2099-12-01T12:00:00+07:00");
        content.put("compensation", Map.of("disclosed", false));
        var draft = drafts.create(owner.id(), new JobRequests.Create(content));
        var pending = submissions.submit(owner.id(), draft.id(), draft.workingRevision().id(),
                new JobSubmissionService.Confirmation(draft.version(), draft.workingRevision().version(), true));
        publication.decide(staff.id(), pending.workingRevision().id(),
                new JobPublicationService.Decision(pending.version(), pending.workingRevision().version(), "APPROVED", null), null, null);
        database.update("UPDATE users SET email_verified=FALSE WHERE id=?", owner.id());
        http.perform(get("/api/jobs/" + draft.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationsAvailable").value(true));
        http.perform(get("/api/jobs/" + draft.id()).header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Nội dung"))
                .andExpect(jsonPath("$.data.assignments").doesNotExist());
        http.perform(get("/api/staff/job-revisions").header("Authorization", staff.authorization())
                .header("X-Guardian-Portal", "staff")).andExpect(status().isOk());
        http.perform(get("/api/companies/me/jobs").header("Authorization", staff.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test void suspendedUserLosesProtectedReadsButKeepsAnonymousRecoveryAndPublicDetailRules() throws Exception {
        var user = account();
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", user.id());
        for (String path : List.of("/api/auth/me", "/api/v1/candidates/profile", "/api/me/notifications")) {
            http.perform(get(path).header("Authorization", user.authorization()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("40304 ACCOUNT_SUSPENDED: Account is suspended"));
        }
        http.perform(post("/api/auth/forgot-password").header("Authorization", user.authorization())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"unknown@example.vn\"}"))
                .andExpect(status().isOk());
    }

    private long member(Actor user, long company, String role) {
        return database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,?,'ACTIVE',now()) RETURNING id",
                Long.class, company, user.id(), role);
    }

    private Actor staffAccount() {
        var user = account();
        database.update("UPDATE users SET role='STAFF' WHERE id=?", user.id());
        String email = database.queryForObject("SELECT email FROM users WHERE id=?", String.class, user.id());
        return new Actor(user.id(), "Bearer " + tokens.generateAccessToken(user.id(), "staff", email, "STAFF", Portal.STAFF));
    }

    private void verifyEmail(Actor user) {
        emailProof.request(user.id());
        String email = database.queryForObject("SELECT email FROM users WHERE id=?", String.class, user.id());
        emailProof.confirm(user.id(), mailbox.get(email));
    }

    private Actor account() {
        String username = "access_" + UUID.randomUUID().toString().replace("-", "");
        var user = auth.register(new RegisterUserRequest("Minh", "Nguyễn", username, username + "@example.vn", "GuardWork123!"));
        return new Actor(user.id(), "Bearer " + tokens.generateAccessToken(user.id(), username, user.email(), "USER"));
    }

    private long company(Actor owner, String state) {
        return new TransactionTemplate(transactions).execute(status -> {
            long id = database.queryForObject("INSERT INTO companies(name,tax_code,email,registration_certificate_url,verification_status,owner_user_id) VALUES ('Test Company',?,?,'private-evidence',?,?) RETURNING id",
                    Long.class, "access_" + UUID.randomUUID(), "company_" + UUID.randomUUID() + "@example.vn", state, owner.id());
            database.update("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'OWNER','ACTIVE',now())", id, owner.id());
            return id;
        });
    }

    private record Actor(long id, String authorization) {}
}
