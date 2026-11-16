package com.guardwork.backend.company;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.guardwork.backend.auth.service.JwtService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class PublicCompanyHttpTest {
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

  private long company(long ownerId, String name, boolean verified, boolean banned) {
    String taxCode = "GW-PUBLIC-COMPANY-" + System.nanoTime();
    return tx.execute(
        status -> {
          Long companyId =
              jdbc.queryForObject(
                  "INSERT INTO companies (name, tax_code, registration_certificate_url, email, verification_status, "
                      + "owner_user_id, is_banned) VALUES (?, ?, 'https://example.invalid/cert.pdf', ?, ?, ?, ?) RETURNING id",
                  Long.class,
                  name,
                  taxCode,
                  name.toLowerCase().replace(" ", "") + "@guardwork.test",
                  verified ? "VERIFIED" : "PENDING",
                  ownerId,
                  banned);
          jdbc.update(
              "INSERT INTO company_memberships (company_id, user_id, role, status, joined_at) VALUES (?, ?, 'OWNER', 'ACTIVE', now())",
              companyId,
              ownerId);
          return companyId;
        });
  }

  private long insertPosting(
      long companyId,
      long ownerId,
      String title,
      String industry,
      String location,
      long salaryMin,
      long salaryMax,
      Instant deadline) {
    String content =
        String.format(
            "{\"title\":\"%s\",\"description\":\"Public company role description for %s\","
                + "\"location\":\"%s\",\"industry\":\"%s\",\"employmentType\":\"FULL_TIME\","
                + "\"workArrangement\":\"ONSITE\",\"headcount\":2,"
                + "\"compensation\":{\"disclosed\":true,\"minimum\":%d,\"maximum\":%d,"
                + "\"currency\":\"VND\",\"payPeriod\":\"MONTH\"},\"deadline\":\"%s\"}",
            title, title, location, industry, salaryMin, salaryMax, deadline);
    return tx.execute(
        status -> {
          long jobId =
              jdbc.queryForObject(
                  "INSERT INTO job_postings (company_id, creator_user_id, status, owner_responsible, created_at, updated_at, version) "
                      + "VALUES (?, ?, 'DRAFT', true, now(), now(), 0) RETURNING id",
                  Long.class,
                  companyId,
                  ownerId);
          long revisionId =
              jdbc.queryForObject(
                  "INSERT INTO job_posting_revisions (job_id, revision_number, content, state, creator_user_id, "
                      + "submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at) "
                      + "VALUES (?, 1, CAST(? AS jsonb), 'APPROVED', ?, ?, now(), ?, now(), 2, now()) RETURNING id",
                  Long.class,
                  jobId,
                  content,
                  ownerId,
                  ownerId,
                  ownerId);
          jdbc.update(
              "UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = ?, "
                  + "approved_deadline = ?, version = 2, updated_at = now() WHERE id = ?",
              revisionId,
              Timestamp.from(deadline),
              jobId);
          return jobId;
        });
  }

  @Test
  void companySearchGroupsOnlyPubliclyVisiblePostings() throws Exception {
    String token = "COMPANYSEARCH" + System.nanoTime();
    long owner = uniqueUser("company-search", true);
    long visibleCompany =
        company(owner, "Visible Employer " + token, true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    insertPosting(
        visibleCompany,
        owner,
        "Backend Engineer " + token,
        "Software",
        "Ha Noi",
        15_000_000,
        25_000_000,
        now.plus(30, ChronoUnit.DAYS));
    insertPosting(
        visibleCompany,
        owner,
        "Platform Engineer " + token,
        "Software",
        "Da Nang",
        20_000_000,
        35_000_000,
        now.plus(20, ChronoUnit.DAYS));

    long unverifiedCompany =
        company(uniqueUser("unverified-company", true), "Pending Employer " + token, false, false);
    insertPosting(
        unverifiedCompany,
        uniqueUser("unverified-owner", true),
        "Hidden pending " + token,
        "Software",
        "Ha Noi",
        10_000_000,
        12_000_000,
        now.plus(30, ChronoUnit.DAYS));

    long bannedCompany =
        company(uniqueUser("banned-company", true), "Banned Employer " + token, true, true);
    insertPosting(
        bannedCompany,
        uniqueUser("banned-owner", true),
        "Hidden banned " + token,
        "Software",
        "Ha Noi",
        10_000_000,
        12_000_000,
        now.plus(30, ChronoUnit.DAYS));

    long suspendedPosting =
        insertPosting(
            visibleCompany,
            owner,
            "Hidden suspended " + token,
            "Software",
            "Ha Noi",
            10_000_000,
            12_000_000,
            now.plus(30, ChronoUnit.DAYS));
    jdbc.update("UPDATE job_postings SET staff_suspended = TRUE WHERE id = ?", suspendedPosting);

    long expiredPosting =
        insertPosting(
            visibleCompany,
            owner,
            "Hidden expired " + token,
            "Software",
            "Ha Noi",
            10_000_000,
            12_000_000,
            now.minus(1, ChronoUnit.DAYS));
    jdbc.update("UPDATE job_postings SET status = 'PUBLISHED' WHERE id = ?", expiredPosting);

    http.perform(get("/api/companies/public").param("keyword", token))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(visibleCompany))
        .andExpect(jsonPath("$.data.content[0].name").value("Visible Employer " + token))
        .andExpect(jsonPath("$.data.content[0].summary.roleCount").value(2))
        .andExpect(jsonPath("$.data.content[0].summary.industries[0].label").value("Software"))
        .andExpect(jsonPath("$.data.content[0].summary.industries[0].count").value(2))
        .andExpect(jsonPath("$.data.content[0].summary.locations.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].summary.salaryBands.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].summary.salaryBands[0].minimum").value(15_000_000))
        .andExpect(jsonPath("$.data.content[0].summary.salaryBands[0].maximum").value(35_000_000))
        .andExpect(jsonPath("$.data.content[0].summary.disclosedSalaryCount").value(2))
        .andExpect(jsonPath("$.data.content[0].summary.headcountTotal").value(4))
        .andExpect(jsonPath("$.data.content[0].summary.deadlines.length()").value(2));
  }

  @Test
  void verifiedCompaniesWithoutVisiblePostingsRemainDiscoverable() throws Exception {
    String token = "IDLECOMPANY" + System.nanoTime();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    long idleOwner = uniqueUser("idle-company", true);
    long idle = company(idleOwner, "Zeta Idle Employer " + token, true, false);
    insertPosting(
        idle,
        idleOwner,
        "Expired Role " + token,
        "Software",
        "Ha Noi",
        10_000_000,
        12_000_000,
        now.minus(1, ChronoUnit.DAYS));

    long activeOwner = uniqueUser("active-company", true);
    long active = company(activeOwner, "Alpha Active Employer " + token, true, false);
    insertPosting(
        active,
        activeOwner,
        "Live Role " + token,
        "Software",
        "Ha Noi",
        10_000_000,
        12_000_000,
        now.plus(30, ChronoUnit.DAYS));

    long pending = company(uniqueUser("idle-pending", true), "Pending Idle Employer " + token, false, false);
    long banned = company(uniqueUser("idle-banned", true), "Banned Idle Employer " + token, true, true);

    // Idle verified companies are listed with a zero role count and sort after companies with roles,
    // even when their name sorts first. Pending and banned companies stay hidden.
    http.perform(get("/api/companies/public").param("keyword", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(active))
        .andExpect(jsonPath("$.data.content[0].summary.roleCount").value(1))
        .andExpect(jsonPath("$.data.content[1].id").value(idle))
        .andExpect(jsonPath("$.data.content[1].name").value("Zeta Idle Employer " + token))
        .andExpect(jsonPath("$.data.content[1].summary.roleCount").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.locations.length()").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.industries.length()").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.salaryBands.length()").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.disclosedSalaryCount").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.headcountTotal").value(0))
        .andExpect(jsonPath("$.data.content[1].summary.deadlines.length()").value(0));

    http.perform(get("/api/companies/public/{id}", idle))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.id").value(idle))
        .andExpect(jsonPath("$.data.name").value("Zeta Idle Employer " + token))
        .andExpect(jsonPath("$.data.summary.roleCount").value(0))
        .andExpect(jsonPath("$.data.summary.industries.length()").value(0));

    http.perform(get("/api/companies/public/{id}/roles", idle))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0))
        .andExpect(jsonPath("$.data.pageNumber").value(0))
        .andExpect(jsonPath("$.data.pageSize").value(20))
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.totalPages").value(0))
        .andExpect(jsonPath("$.data.first").value(true))
        .andExpect(jsonPath("$.data.last").value(true));

    // Pages beyond the last role must return an empty page, not fail.
    http.perform(get("/api/companies/public/{id}/roles", idle).param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0));
    http.perform(
            get("/api/companies/public/{id}/roles", active).param("page", "5").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0));

    http.perform(get("/api/companies/public/{id}", pending))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("40400 COMPANY_NOT_FOUND: Company not found"));
    http.perform(get("/api/companies/public/{id}", banned)).andExpect(status().isNotFound());
    http.perform(get("/api/companies/public/{id}/roles", pending)).andExpect(status().isNotFound());
  }

  @Test
  void dossierReturnsOnlyVisibleRolesAndUnknownCompaniesAreNotFound() throws Exception {
    String token = "COMPANYDOSSIER" + System.nanoTime();
    long owner = uniqueUser("company-dossier", true);
    long company = company(owner, "Dossier Employer " + token, true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    long visible =
        insertPosting(
            company,
            owner,
            "Visible Role " + token,
            "Logistics",
            "Ho Chi Minh",
            18_000_000,
            24_000_000,
            now.plus(14, ChronoUnit.DAYS));
    long hidden =
        insertPosting(
            company,
            owner,
            "Hidden Role " + token,
            "Logistics",
            "Ha Noi",
            18_000_000,
            24_000_000,
            now.plus(14, ChronoUnit.DAYS));
    jdbc.update("UPDATE job_postings SET staff_suspended = TRUE WHERE id = ?", hidden);
    long visibleSecond =
        insertPosting(
            company,
            owner,
            "Second Visible Role " + token,
            "Logistics",
            "Ho Chi Minh",
            18_000_000,
            24_000_000,
            now.plus(10, ChronoUnit.DAYS));

    http.perform(get("/api/companies/public/{id}", company))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.id").value(company))
        .andExpect(jsonPath("$.data.name").value("Dossier Employer " + token))
        .andExpect(jsonPath("$.data.summary.roleCount").value(2))
        .andExpect(jsonPath("$.data.roles").doesNotExist());

    http.perform(
            get("/api/companies/public/{id}/roles", company).param("page", "0").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.pageNumber").value(0))
        .andExpect(jsonPath("$.data.pageSize").value(1))
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(visibleSecond))
        .andExpect(jsonPath("$.data.content[0].title").value("Second Visible Role " + token))
        .andExpect(jsonPath("$.data.content[0].salary.minimum").value(18_000_000))
        .andExpect(jsonPath("$.data.content[0].applicationsAvailable").value(true));
    http.perform(
            get("/api/companies/public/{id}/roles", company).param("page", "1").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].id").value(visible));

    http.perform(get("/api/companies/public/{id}", 999_999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("40400 COMPANY_NOT_FOUND: Company not found"));
    http.perform(get("/api/companies/public/{id}/roles", 999_999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("40400 COMPANY_NOT_FOUND: Company not found"));
  }

  @Test
  void rolesExposeApplicationCounts() throws Exception {
    String token = "COMPANYAPPS" + System.nanoTime();
    long owner = uniqueUser("company-apps", true);
    long company = company(owner, "Apps Employer " + token, true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    long busyRole =
        insertPosting(
            company,
            owner,
            "Busy Role " + token,
            "Software",
            "Ha Noi",
            15_000_000,
            25_000_000,
            now.plus(30, ChronoUnit.DAYS));
    long quietRole =
        insertPosting(
            company,
            owner,
            "Quiet Role " + token,
            "Software",
            "Ha Noi",
            15_000_000,
            25_000_000,
            now.plus(20, ChronoUnit.DAYS));
    applyFor(company, busyRole);
    applyFor(company, busyRole);
    applyFor(company, quietRole);

    // Roles are ordered by deadline ascending, then by role id.
    http.perform(get("/api/companies/public/{id}/roles", company))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(quietRole))
        .andExpect(jsonPath("$.data.content[0].applicationCount").value(1))
        .andExpect(jsonPath("$.data.content[1].id").value(busyRole))
        .andExpect(jsonPath("$.data.content[1].applicationCount").value(2));
  }

  private void applyFor(long companyId, long jobId) {
    long candidateUser = uniqueUser("applicant", true);
    tx.execute(
        status -> {
          UUID candidateId =
              jdbc.queryForObject(
                  "INSERT INTO candidate_profiles(user_id,phone_number) VALUES (?,'0901234567') RETURNING id",
                  UUID.class,
                  candidateUser);
          Long revisionId =
              jdbc.queryForObject(
                  "SELECT published_revision_id FROM job_postings WHERE id = ?",
                  Long.class,
                  jobId);
          jdbc.update(
              "INSERT INTO applications(id,candidate_id,job_id,company_id,submitted_revision_id,status,applied_at,updated_at) "
                  + "VALUES (?,?,?,?,?, 'SUBMITTED', now(), now())",
              UUID.randomUUID(),
              candidateId,
              jobId,
              companyId,
              revisionId);
          return null;
        });
  }

  @Test
  void authenticatedUserAwaitingEmailProofCanReadPublicCompanies() throws Exception {
    long userId = uniqueUser("company-public-unverified", false);
    http.perform(get("/api/companies/public").header("Authorization", bearer(userId)))
        .andExpect(status().isOk());
  }
}
