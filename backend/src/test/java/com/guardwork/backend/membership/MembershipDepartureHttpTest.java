package com.guardwork.backend.membership;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(MembershipDepartureHttpTest.ControlledDelivery.class)
class MembershipDepartureHttpTest {
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate database;
    @Autowired
    CapturingMailer mailer;
    @Autowired
    MutableClock clock;
    @Autowired
    com.guardwork.backend.notification.service.NotificationDispatcher dispatcher;
    MockMvc http;

    @BeforeEach
    void setup() {
        mailer.reset();
        clock.now = java.time.Instant.parse("2026-10-08T16:59:59Z");
        http = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(com.guardwork.backend.invitation.controller.InvitationPrivacyFilter.class))
                .apply(springSecurity()).build();
    }

    @Test
    void ownerRemovesRecruiterAndSameJwtLosesCompanyAccessButReceivesPrivateInbox() throws Exception {
        String owner = account(), recipient = account();
        long company = verifiedCompany(owner);
        String secret = invite(owner, email(recipient));
        var accepted = mapper.readTree(secretPost("accept", recipient, secret, true).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
        long membership = accepted.path("membershipId").asLong();
        http.perform(get("/api/companies/me/members").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REMOVED"))
                .andExpect(jsonPath("$.data.membershipId").value(membership))
                .andExpect(jsonPath("$.data.endedAt").value("2026-10-08T16:59:59Z"));
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(get("/api/companies/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data").isEmpty());
        http.perform(get("/api/companies/" + company).header("Authorization", recipient))
                .andExpect(status().isNotFound());
        http.perform(get("/api/companies/me/members").header("Authorization", recipient))
                .andExpect(status().isForbidden());
        leave(recipient).andExpect(status().isForbidden());
        secretPost("accept", recipient, secret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("REMOVED"));
        var notification = mapper.readTree(http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].eventType").value("MEMBERSHIP_REMOVED")).andReturn()
                .getResponse().getContentAsString())
                .path("data").path("content").get(0);
        long notificationId = notification.path("id").asLong();
        http.perform(patch("/api/me/notifications/" + notificationId + "/read").header("Authorization", recipient))
                .andExpect(status().isOk());
        http.perform(patch("/api/me/notifications/" + notificationId + "/read").header("Authorization", owner))
                .andExpect(status().isNotFound());
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isNotFound());
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void departureRetainsOriginalReceiptAndAllowsNewCompanyWithoutNotifyingDepartingUser() throws Exception {
        String owner = account(), recipient = account();
        long oldCompany = verifiedCompany(owner);
        String secret = invite(owner, email(recipient));
        long membership = accept(recipient, secret);
        leave(recipient)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.membershipId").value(membership))
                .andExpect(jsonPath("$.data.status").value("LEFT"));
        assertNoCompanyAccess(recipient, oldCompany);
        secretPost("accept", recipient, secret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("LEFT"));
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        http.perform(get("/api/me/notifications").header("Authorization", owner))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        long newCompany = verifiedCompany(recipient);
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership.companyId").value(newCompany));
        secretPost("accept", recipient, secret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("LEFT"))
                .andExpect(jsonPath("$.data.membershipId").value(membership));
        http.perform(get("/api/companies/" + oldCompany).header("Authorization", recipient))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedRecruiterCanRejoinThroughNewInvitationWithoutChangingOriginalPeriod() throws Exception {
        String owner = account(), recipient = account();
        verifiedCompany(owner);
        String originalSecret = invite(owner, email(recipient));
        long original = accept(recipient, originalSecret);
        http.perform(delete("/api/companies/me/members/" + original).header("Authorization", owner))
                .andExpect(status().isOk());
        clock.now = clock.now.plusSeconds(100);
        String newSecret = invite(owner, email(recipient));
        long replacement = accept(recipient, newSecret);
        assertThat(replacement).isNotEqualTo(original);
        secretPost("accept", recipient, originalSecret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("REMOVED"))
                .andExpect(jsonPath("$.data.membershipId").value(original));
        secretPost("accept", recipient, newSecret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("ACTIVE"));
        http.perform(delete("/api/companies/me/members/" + original).header("Authorization", owner))
                .andExpect(status().isNotFound());
        http.perform(get("/api/companies/me/members").header("Authorization", owner))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void removalFreesEligibilityForAnotherCompanyAndOldReceiptCannotGrantAccess() throws Exception {
        String owner = account(), otherOwner = account(), recipient = account();
        long originalCompany = verifiedCompany(owner);
        long otherCompany = verifiedCompany(otherOwner);
        String originalSecret = invite(owner, email(recipient)), otherSecret = invite(otherOwner, email(recipient));
        long membership = accept(recipient, originalSecret);
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isOk());
        accept(recipient, otherSecret);
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership.companyId").value(otherCompany));
        secretPost("accept", recipient, originalSecret, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("REMOVED"));
        http.perform(get("/api/companies/" + originalCompany).header("Authorization", recipient))
                .andExpect(status().isNotFound());
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.content[0].companyId").value(originalCompany));
    }

    @Test
    void ownerProtectionReservedAdminRolesAndPrivateTargetsAreEnforced() throws Exception {
        String owner = account(), recipient = account(), stranger = account(), otherOwner = account();
        long company = verifiedCompany(owner);
        verifiedCompany(otherOwner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        long ownerMembership = mapper
                .readTree(http.perform(get("/api/companies/me/members").header("Authorization", owner)).andReturn()
                        .getResponse().getContentAsString())
                .path("data").path("content").get(0).path("membershipId").asLong();
        leave(owner).andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.message").value("40912 OWNER_MEMBERSHIP_PROTECTED: Owner membership cannot end"));
        http.perform(delete("/api/companies/me/members/" + ownerMembership).header("Authorization", owner))
                .andExpect(status().isConflict());
        for (String actor : new String[] { recipient, stranger }) {
            http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", actor))
                    .andExpect(status().isForbidden());
            http.perform(get("/api/companies/me/members").header("Authorization", actor))
                    .andExpect(status().isForbidden());
        }
        String absent = http
                .perform(delete("/api/companies/me/members/" + Long.MAX_VALUE).header("Authorization", otherOwner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", otherOwner))
                .andExpect(status().isNotFound()).andExpect(content().json(absent));
        database.update("UPDATE company_memberships SET role='ADMIN' WHERE id=?", membership);
        leave(recipient).andExpect(status().isForbidden());
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isForbidden());
        database.update("UPDATE company_memberships SET role='RECRUITER' WHERE id=?", membership);
        database.update("UPDATE companies SET is_banned=true WHERE id=?", company);
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isNotFound());
        leave(recipient).andExpect(status().isNotFound());
        database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
        long ownerId = userId(owner), recipientId = userId(recipient);
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", ownerId);
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isForbidden());
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", recipientId);
        leave(recipient).andExpect(status().isForbidden());
        http.perform(delete("/api/companies/me/members/" + membership)).andExpect(status().isUnauthorized());
    }

    @Test
    void failedInboxDeliveryCannotUndoRemovalAndReplayDoesNotDuplicateNotification() throws Exception {
        String owner = account(), recipient = account();
        long company = verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        long recipientId = userId(recipient);
        database.execute(
                "CREATE FUNCTION gw007_reject_inbox() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled inbox failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute(
                "CREATE TRIGGER gw007_reject_inbox BEFORE INSERT ON user_notifications FOR EACH ROW WHEN (NEW.recipient_user_id="
                        + recipientId + ") EXECUTE FUNCTION gw007_reject_inbox()");
        try {
            http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REMOVED"));
            assertNoCompanyAccess(recipient, company);
            http.perform(get("/api/me/notifications").header("Authorization", recipient))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        } finally {
            database.execute("DROP TRIGGER gw007_reject_inbox ON user_notifications");
            database.execute("DROP FUNCTION gw007_reject_inbox()");
        }
        UUID event = database.queryForObject(
                "SELECT event_id FROM notification_outbox WHERE recipient_user_id=? AND event_type='MEMBERSHIP_REMOVED'",
                UUID.class, recipientId);
        dispatcher.replay(event);
        dispatcher.replay(event);
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiredAuditFailureRollsBackRemovalAndDepartureWithNoNotification() throws Exception {
        String owner = account(), recipient = account();
        verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        database.execute(
                "CREATE FUNCTION gw007_reject_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled audit failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
        database.execute(
                "CREATE TRIGGER gw007_reject_audit BEFORE INSERT ON company_membership_audit_events FOR EACH ROW WHEN (NEW.membership_id="
                        + membership
                        + " AND NEW.event_type IN ('MEMBERSHIP_REMOVED','MEMBERSHIP_LEFT')) EXECUTE FUNCTION gw007_reject_audit()");
        try {
            http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                    .andExpect(status().isInternalServerError());
            leave(recipient).andExpect(status().isInternalServerError());
            http.perform(get("/api/auth/me").header("Authorization", recipient))
                    .andExpect(jsonPath("$.data.activeMembership.companyRole").value("RECRUITER"));
            http.perform(get("/api/me/notifications").header("Authorization", recipient))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        } finally {
            database.execute("DROP TRIGGER gw007_reject_audit ON company_membership_audit_events");
            database.execute("DROP FUNCTION gw007_reject_audit()");
        }
        leave(recipient).andExpect(status().isOk());
    }

    @Test
    void memberListPaginationAndCurrentOwnerBoundaryRemainValidated() throws Exception {
        String owner = account(), recipient = account();
        verifiedCompany(owner);
        accept(recipient, invite(owner, email(recipient)));
        http.perform(get("/api/companies/me/members?size=1&page=1").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value(email(recipient)))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.pageSize").value(1));
        for (String query : new String[] { "?page=-1", "?size=0", "?size=101", "?page=invalid" })
            http.perform(get("/api/companies/me/members" + query).header("Authorization", owner))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void companySuspensionCommittedBeforeDeparturePreventsAllMembershipEffects() throws Exception {
        String owner = account(), recipient = account();
        long company = verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        try (var connection = database.getDataSource().getConnection();
                var worker = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("UPDATE companies SET is_banned=true WHERE id=?")) {
                update.setLong(1, company);
                update.executeUpdate();
            }
            var departing = worker.submit(() -> leave(recipient).andReturn().getResponse().getStatus());
            try {
                awaitDatabaseLock();
                connection.commit();
            } finally {
                connection.rollback();
            }
            assertThat(departing.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        } finally {
            database.update("UPDATE companies SET is_banned=false WHERE id=?", company);
        }
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership.companyId").value(company));
        assertThat(database.queryForObject(
                "SELECT count(*) FROM company_membership_audit_events WHERE membership_id=? AND event_type='MEMBERSHIP_LEFT'",
                Long.class, membership)).isZero();
    }

    @Test
    void changedTargetRoleCommittedBeforeRemovalIsRecheckedWithoutAuditOrNotification() throws Exception {
        String owner = account(), recipient = account();
        long company = verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        try (var connection = database.getDataSource().getConnection();
                var worker = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var companyLock = connection
                    .prepareStatement("SELECT id FROM companies WHERE id=? FOR NO KEY UPDATE")) {
                companyLock.setLong(1, company);
                companyLock.executeQuery().close();
            }
            try (var role = connection.prepareStatement("UPDATE company_memberships SET role='ADMIN' WHERE id=?")) {
                role.setLong(1, membership);
                role.executeUpdate();
            }
            var removing = worker.submit(
                    () -> http.perform(delete("/api/companies/me/members/" + membership).header("Authorization", owner))
                            .andReturn().getResponse().getStatus());
            try {
                awaitDatabaseLock();
                connection.commit();
            } finally {
                connection.rollback();
            }
            assertThat(removing.get(10, TimeUnit.SECONDS)).isEqualTo(403);
        } finally {
            database.update("UPDATE company_memberships SET role='RECRUITER' WHERE id=?", membership);
        }
        http.perform(get("/api/companies/me/members").header("Authorization", owner))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void changedActorRoleCommittedBeforeDepartureIsRecheckedFromLockedMembership() throws Exception {
        String owner = account(), recipient = account();
        long company = verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        try (var connection = database.getDataSource().getConnection();
                var worker = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var companyLock = connection
                    .prepareStatement("SELECT id FROM companies WHERE id=? FOR NO KEY UPDATE")) {
                companyLock.setLong(1, company);
                companyLock.executeQuery().close();
            }
            try (var role = connection.prepareStatement("UPDATE company_memberships SET role='ADMIN' WHERE id=?")) {
                role.setLong(1, membership);
                role.executeUpdate();
            }
            var leaving = worker.submit(() -> leave(recipient).andReturn().getResponse().getStatus());
            try {
                awaitDatabaseLock();
                connection.commit();
            } finally {
                connection.rollback();
            }
            assertThat(leaving.get(10, TimeUnit.SECONDS)).isEqualTo(403);
        } finally {
            database.update("UPDATE company_memberships SET role='RECRUITER' WHERE id=?", membership);
        }
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership.companyRole").value("RECRUITER"));
    }

    void awaitDatabaseLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting = database.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
                    Long.class);
            if (waiting > 0)
                return;
            Thread.sleep(20);
        }
        throw new AssertionError("protected HTTP write did not reach the held PostgreSQL guard");
    }

    @Test
    void bodylessDepartureTargetsCurrentMembershipAndAuthContextOmitsDraftSelector() throws Exception {
        String owner = account(), recipient = account();
        verifiedCompany(owner);
        long membership = accept(recipient, invite(owner, email(recipient)));
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership.companyId").exists())
                .andExpect(jsonPath("$.data.activeMembership.membershipId").doesNotExist());
        leave(recipient).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipId").value(membership))
                .andExpect(jsonPath("$.data.status").value("LEFT"));
        leave(recipient).andExpect(status().isForbidden());
    }

    @Test
    void concurrentBodylessDeparturesEndOnceAndRequireAnActiveMembership() throws Exception {
        String owner = account(), recipient = account();
        verifiedCompany(owner);
        long period = accept(recipient, invite(owner, email(recipient)));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        int first, second;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> {
                ready.countDown();
                start.await();
                return leave(recipient).andReturn().getResponse().getStatus();
            });
            var two = workers.submit(() -> {
                ready.countDown();
                start.await();
                return leave(recipient).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first = one.get(10, TimeUnit.SECONDS);
            second = two.get(10, TimeUnit.SECONDS);
        }
        assertThat(List.of(first, second)).containsExactlyInAnyOrder(200, 403);
        assertThat(database.queryForObject(
                "SELECT count(*) FROM company_membership_audit_events WHERE membership_id=? AND event_type='MEMBERSHIP_LEFT'",
                Long.class, period)).isEqualTo(1);
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(get("/api/me/notifications").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    org.springframework.test.web.servlet.ResultActions leave(String actor) throws Exception {
        return http.perform(post("/api/companies/me/membership/leave").header("Authorization", actor));
    }

    long accept(String recipient, String secret) throws Exception {
        return mapper.readTree(secretPost("accept", recipient, secret, true).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).path("data").path("membershipId").asLong();
    }

    void assertNoCompanyAccess(String recipient, long formerCompany) throws Exception {
        http.perform(get("/api/auth/me").header("Authorization", recipient))
                .andExpect(jsonPath("$.data.activeMembership").isEmpty());
        http.perform(get("/api/companies/" + formerCompany).header("Authorization", recipient))
                .andExpect(status().isNotFound());
        http.perform(get("/api/companies/me/members").header("Authorization", recipient))
                .andExpect(status().isForbidden());
        http.perform(post("/api/companies/me/invitations").header("Authorization", recipient)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"forbidden@example.vn\"}")).andExpect(status().isForbidden());
    }

    org.springframework.test.web.servlet.ResultActions secretPost(String action, String token, String secret,
            Boolean confirm) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("secret", secret);
        if (confirm != null)
            body.put("confirm", confirm);
        return http.perform(post("/api/company-invitations/" + action).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    String email(String token) throws Exception {
        return mapper.readTree(http.perform(get("/api/auth/me").header("Authorization", token)).andReturn()
                .getResponse().getContentAsString()).path("data").path("email").asText();
    }

    String invite(String owner, String email) throws Exception {
        http.perform(post("/api/companies/me/invitations").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email)))).andExpect(status().isCreated());
        return mailer.secrets.getLast();
    }

    long verifiedCompany(String owner) throws Exception {
        long id = createdCompany(owner);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", id);
        return id;
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class ControlledDelivery {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        CapturingMailer capturingMailer() {
            return new CapturingMailer();
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends java.time.Clock {
        volatile java.time.Instant now = java.time.Instant.parse("2026-10-08T16:59:59Z");

        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public java.time.Instant instant() {
            return now;
        }
    }

    static class CapturingMailer implements com.guardwork.backend.invitation.mail.CompanyInvitationMailer {
        final java.util.List<String> secrets = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> recipients = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile com.guardwork.backend.invitation.model.DeliveryStatus outcome;
        volatile CountDownLatch blockNext;
        volatile CountDownLatch release;
        final java.util.concurrent.atomic.AtomicBoolean blocking = new java.util.concurrent.atomic.AtomicBoolean();

        void reset() {
            secrets.clear();
            recipients.clear();
            blockNext = null;
            release = null;
            blocking.set(false);
            outcome = com.guardwork.backend.invitation.model.DeliveryStatus.PROVIDER_ACCEPTED;
        }

        public com.guardwork.backend.invitation.model.DeliveryStatus send(String recipient, String company,
                java.time.Instant expiry, String secret) {
            recipients.add(recipient);
            secrets.add(secret);
            if (blockNext != null && blocking.compareAndSet(false, true)) {
                CountDownLatch blocked = blockNext;
                blockNext = null;
                blocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("delivery race did not release");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    blocking.set(false);
                }
                return com.guardwork.backend.invitation.model.DeliveryStatus.FAILED;
            }
            return outcome;
        }
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString())).header("Authorization", token)
                )
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String account() throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
        return account(name + "@example.vn");
    }

    String account(String email) throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
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
                "email", taxCode + "@company.vn", "registrationCertificateUrl",
                "https://storage.guardwork.vn/giấy-phép.pdf"));
    }
}
