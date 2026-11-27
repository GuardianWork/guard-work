package com.guardwork.backend.company;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.guardwork.backend.auth.service.JwtService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class CompanyProfileHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtService jwt;
  @Autowired PlatformTransactionManager transactionManager;
  MockMvc http;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    tx = new TransactionTemplate(transactionManager);
  }

  private long uniqueUser(String prefix, boolean emailVerified) {
    String username = prefix + "_" + System.nanoTime();
    String email = username + "@guardwork.test";
    return tx.execute(
        status ->
            jdbc.queryForObject(
                "INSERT INTO users (username, email, first_name, last_name, password, role, status, email_verified) "
                    + "VALUES (?, ?, 'Company', 'Tester', '$2a$10$.Sw9NzCJH98UmXsd3ep4punGVyL7FwuBWXWykQDXbDi7wTSj3SK/S', 'USER', 'ACTIVE', ?) RETURNING id",
                Long.class,
                username,
                email,
                emailVerified));
  }

  private String bearer(long userId) {
    var identity = jdbc.queryForMap("SELECT username,email FROM users WHERE id=?", userId);
    return "Bearer "
        + jwt.generateAccessToken(
            userId,
            (String) identity.get("username"),
            (String) identity.get("email"),
            "USER");
  }

  private long companyWithMembership(long ownerId, String name, String membershipRole) {
    String taxCode = "GW-PROFILE-COMPANY-" + System.nanoTime();
    return tx.execute(
        status -> {
          Long companyId =
              jdbc.queryForObject(
                  "INSERT INTO companies (name, tax_code, registration_certificate_url, email, verification_status, "
                      + "owner_user_id, is_banned) VALUES (?, ?, 'https://example.invalid/cert.pdf', ?, 'VERIFIED', ?, FALSE) RETURNING id",
                  Long.class,
                  name,
                  taxCode,
                  name.toLowerCase().replace(" ", "") + "@guardwork.test",
                  ownerId);
          addMembership(companyId, ownerId, membershipRole);
          return companyId;
        });
  }

  // A company must keep exactly one declared active OWNER membership; additional
  // members (ADMIN/RECRUITER) join through this helper.
  private void addMembership(long companyId, long userId, String role) {
    tx.execute(
        status ->
            jdbc.update(
                "INSERT INTO company_memberships (company_id, user_id, role, status, joined_at) VALUES (?, ?, ?, 'ACTIVE', now())",
                companyId,
                userId,
                role));
  }

  @Test
  void ownerMayUpdateAndTheDossierPublishesTheProfile() throws Exception {
    String token = "CONTENTSEED" + System.nanoTime();
    long owner = uniqueUser("content-owner", true);
    long company = companyWithMembership(owner, "Content Employer " + token, "OWNER");

    String body =
        "{\"industry\":\"Fintech\","
            + "\"introduction\":\"Chúng tôi xây dựng hạ tầng thanh toán cho doanh nghiệp Việt Nam.\","
            + "\"workAddress\":\"Tầng 12, Tòa nhà Sài Gòn, Quận 1, TP.HCM\","
            + "\"workingHours\":\"Thứ 2 - Thứ 6, 08:30 - 17:30\","
            + "\"companyScale\":{\"employeeRange\":\"51-200\"},"
            + "\"expertise\":[\"Cloud infrastructure\",\"Payments \",\" \",\"CLOUD INFRASTRUCTURE\"]}";

    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.schemaVersion").value(1))
        .andExpect(jsonPath("$.data.industry").value("Fintech"))
        .andExpect(jsonPath("$.data.workingHours").value("Thứ 2 - Thứ 6, 08:30 - 17:30"))
        .andExpect(jsonPath("$.data.companyScale.employeeRange").value("51-200"))
        .andExpect(jsonPath("$.data.expertise.length()").value(2))
        .andExpect(jsonPath("$.data.expertise[0]").value("Cloud infrastructure"))
        .andExpect(jsonPath("$.data.expertise[1]").value("Payments"));

    String stored = jdbc.queryForObject("SELECT content::text FROM companies WHERE id = ?", String.class, company);
    org.assertj.core.api.Assertions.assertThat(stored).contains("\"schemaVersion\": 1").contains("\"industry\": \"Fintech\"");

    Integer audits =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM company_membership_audit_events WHERE company_id = ? AND event_type = 'COMPANY_PROFILE_UPDATED'",
            Integer.class,
            company);
    org.assertj.core.api.Assertions.assertThat(audits).isEqualTo(1);

    http.perform(get("/api/companies/public/{id}", company))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.profile.schemaVersion").value(1))
        .andExpect(jsonPath("$.data.profile.industry").value("Fintech"))
        .andExpect(jsonPath("$.data.profile.introduction")
            .value("Chúng tôi xây dựng hạ tầng thanh toán cho doanh nghiệp Việt Nam."))
        .andExpect(jsonPath("$.data.profile.workAddress").value("Tầng 12, Tòa nhà Sài Gòn, Quận 1, TP.HCM"))
        .andExpect(jsonPath("$.data.profile.workingHours").value("Thứ 2 - Thứ 6, 08:30 - 17:30"))
        .andExpect(jsonPath("$.data.profile.companyScale.employeeRange").value("51-200"))
        .andExpect(jsonPath("$.data.profile.expertise.length()").value(2))
        .andExpect(jsonPath("$.data.summary.roleCount").value(0));
  }

  @Test
  void adminMayUpdateButRecruiterMayNot() throws Exception {
    String token = "CONTENTRAW" + System.nanoTime();
    long admin = uniqueUser("content-admin", true);
    long recruiter = uniqueUser("content-recruiter", true);
    long adminOwner = uniqueUser("content-admin-owner", true);
    long recruiterOwner = uniqueUser("content-recruiter-owner", true);
    long adminCompany = companyWithMembership(adminOwner, "Admin Employer " + token, "OWNER");
    long recruiterCompany = companyWithMembership(recruiterOwner, "Recruiter Employer " + token, "OWNER");
    addMembership(adminCompany, admin, "ADMIN");
    addMembership(recruiterCompany, recruiter, "RECRUITER");

    String body = "{\"industry\":\"Logistics\"}";
    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.industry").value("Logistics"));

    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(recruiter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("40303 INSUFFICIENT_COMPANY_ROLE: Owner or Admin required"));
  }

  @Test
  void memberlessUserMayNotUpdateAndAnonymousCallerIsRejected() throws Exception {
    String token = "CONTENTSTRANGER" + System.nanoTime();
    long stranger = uniqueUser("content-stranger", true);

    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(stranger))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"industry\":\"Retail\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("40302 NOT_COMPANY_MEMBER: Active Company membership required"));

    http.perform(
            put("/api/companies/me/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"industry\":\"Retail\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidPayloadsAreRejected() throws Exception {
    String token = "CONTENTINVALID" + System.nanoTime();
    long owner = uniqueUser("content-invalid", true);
    companyWithMembership(owner, "Invalid Content Employer " + token, "OWNER");

    String industry101 = "{\"industry\":\"" + "a".repeat(101) + "\"}";
    String introduction2001 = "{\"introduction\":\"" + "a".repeat(2001) + "\"}";
    String workingHoursEnglish = "{\"workingHours\":\"Monday to Friday\"}";
    String workingHoursReversed = "{\"workingHours\":\"Thứ 6 - Thứ 2\"}";
    String workingHoursUnknown = "{\"workingHours\":\"Thứ 1 - Thứ 6\"}";
    String expertiseTooMany = "{\"expertise\":["
        + IntStream.rangeClosed(1, 11).mapToObj(i -> "\"item-" + i + "\"").collect(Collectors.joining(","))
        + "]}";
    String expertiseItemTooLong = "{\"expertise\":[\"" + "a".repeat(51) + "\"]}";
    String unknownRange = "{\"companyScale\":{\"employeeRange\":\"gigantic\"}}";

    expectBadRequest(owner, industry101);
    expectBadRequest(owner, introduction2001);
    expectBadRequest(owner, workingHoursEnglish);
    expectBadRequest(owner, workingHoursReversed);
    expectBadRequest(owner, workingHoursUnknown);
    expectBadRequest(owner, expertiseTooMany);
    expectBadRequest(owner, expertiseItemTooLong);
    expectBadRequest(owner, unknownRange);

    // Boundary values that must pass, including the Chủ nhật day token.
    String introduction2000 = "{\"introduction\":\"" + "a".repeat(2000) + "\"}";
    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(introduction2000))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.introduction").value("a".repeat(2000)));

    String workingHoursSunday = "{\"workingHours\":\"Thứ 2 - Chủ nhật, 08:00 - 17:00\"}";
    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(workingHoursSunday))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workingHours").value("Thứ 2 - Chủ nhật, 08:00 - 17:00"));
  }

  private void expectBadRequest(long owner, String body) throws Exception {
    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void clearingTheProfileRemovesItFromTheDossier() throws Exception {
    String token = "CONTENTCLEAR" + System.nanoTime();
    long owner = uniqueUser("content-clear", true);
    long company = companyWithMembership(owner, "Clear Employer " + token, "OWNER");

    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"industry\":\"Retail\"}"))
        .andExpect(status().isOk());

    http.perform(get("/api/companies/public/{id}", company))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profile.industry").value("Retail"));

    http.perform(
            put("/api/companies/me/profile")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.industry").doesNotExist())
        .andExpect(jsonPath("$.data.schemaVersion").doesNotExist());

    http.perform(get("/api/companies/public/{id}", company))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profile").doesNotExist());

    Integer audits =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM company_membership_audit_events WHERE company_id = ? AND event_type = 'COMPANY_PROFILE_UPDATED'",
            Integer.class,
            company);
    org.assertj.core.api.Assertions.assertThat(audits).isEqualTo(2);
  }

  @Test
  void companyRowInsertedWithoutContentGetsTheEmptyDefault() throws Exception {
    String token = "CONTENTDEFAULT" + System.nanoTime();
    long owner = uniqueUser("content-default", true);
    long company = companyWithMembership(owner, "Default Employer " + token, "OWNER");

    String stored = jdbc.queryForObject("SELECT content::text FROM companies WHERE id = ?", String.class, company);
    org.assertj.core.api.Assertions.assertThat(stored.trim()).isEqualTo("{}");

    http.perform(get("/api/companies/public/{id}", company))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profile").doesNotExist())
        .andExpect(jsonPath("$.data.summary.roleCount").value(0));
  }
}
