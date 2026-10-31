package com.guardwork.backend.job;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ActiveProfiles("test")
class JobSearchIntegrationHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  MockMvc http;
  @Autowired PlatformTransactionManager transactionManager;
  private TransactionTemplate tx;

  private final List<Long> postingIds = new ArrayList<>();
  private final List<Long> companyIds = new ArrayList<>();
  private final List<Long> userIds = new ArrayList<>();

  @BeforeEach
  void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    tx = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    // Revision and job history cannot be deleted by design, so only clear references.
    postingIds.clear();
    companyIds.clear();
    userIds.clear();
  }

  private long uniqueUser(String prefix) {
    String username = prefix + "_" + System.nanoTime();
    String email = username + "@guardwork.test";
    Long id = tx.execute(status -> jdbc.queryForObject(
        "INSERT INTO users (username, email, first_name, last_name, password, role, status, email_verified) "
            + "VALUES (?, ?, 'Search', 'Tester', '$2a$10$.Sw9NzCJH98UmXsd3ep4punGVyL7FwuBWXWykQDXbDi7wTSj3SK/S', 'USER', 'ACTIVE', true) RETURNING id",
        Long.class, username, email));
    userIds.add(id);
    return id;
  }

  private long company(long ownerId, String taxSuffix, String name, boolean verified, boolean banned) {
    String taxCode = "GW-SEARCH-TEST-" + taxSuffix + "-" + System.nanoTime();
    Long id = tx.execute(status -> {
      Long companyId = jdbc.queryForObject(
          "INSERT INTO companies (name, tax_code, registration_certificate_url, email, verification_status, "
              + "owner_user_id, is_banned) VALUES (?, ?, 'https://example.invalid/cert.pdf', ?, ?, ?, ?) RETURNING id",
          Long.class,
          name,
          taxCode,
          name.toLowerCase().replace(" ", "") + "@guardwork.test",
          verified ? "VERIFIED" : "PENDING",
          ownerId,
          banned);
      jdbc.update("INSERT INTO company_memberships (company_id, user_id, role, status, joined_at) VALUES (?, ?, 'OWNER', 'ACTIVE', now())", companyId, ownerId);
      return companyId;
    });
    companyIds.add(id);
    return id;
  }

  private long insertPosting(long companyId, long ownerId, String title, String description, String location,
      String employmentType, String workArrangement, long salaryMin, long salaryMax, boolean disclosed,
      Instant deadline, Instant createdAt) {
    String content = String.format(
        "{\"title\":\"%s\",\"description\":\"%s\",\"location\":\"%s\",\"employmentType\":\"%s\","
            + "\"workArrangement\":\"%s\",\"headcount\":1,\"compensation\":{\"disclosed\":%s,\"minimum\":%d,"
            + "\"maximum\":%d,\"currency\":\"VND\",\"payPeriod\":\"MONTH\"},\"deadline\":\"%s\"}",
        title, description, location, employmentType, workArrangement, disclosed, salaryMin, salaryMax,
        deadline.toString());
    Long postingId = tx.execute(status -> {
      long draftId = jdbc.queryForObject(
          "INSERT INTO job_postings (company_id, creator_user_id, status, owner_responsible, created_at, updated_at, version) "
              + "VALUES (?, ?, 'DRAFT', true, ?, ?, 0) RETURNING id",
          Long.class, companyId, ownerId, Timestamp.from(createdAt), Timestamp.from(createdAt));
      long revisionId = jdbc.queryForObject(
          "INSERT INTO job_posting_revisions (job_id, revision_number, content, state, creator_user_id, "
              + "submitter_user_id, submitted_at, reviewer_user_id, decided_at, version, created_at) "
              + "VALUES (?, 1, CAST(? AS jsonb), 'APPROVED', ?, ?, ?, ?, ?, 2, ?) RETURNING id",
          Long.class, draftId, content, ownerId, ownerId, Timestamp.from(createdAt.plus(1, ChronoUnit.HOURS)), ownerId,
          Timestamp.from(createdAt.plus(2, ChronoUnit.HOURS)), Timestamp.from(createdAt));
      jdbc.update(
          "UPDATE job_postings SET status = 'PUBLISHED', published_revision_id = ?, approved_deadline = ?, "
              + "version = 2, updated_at = ? WHERE id = ?",
          revisionId, Timestamp.from(deadline), Timestamp.from(createdAt.plus(2, ChronoUnit.HOURS)), draftId);
      return draftId;
    });
    postingIds.add(postingId);
    return postingId;
  }

  @Test
  void publishedOnlyAndCompanyCriteria() throws Exception {
    String token = "PUBLISHED-" + System.nanoTime();
    long owner = uniqueUser("publisher");
    long company = company(owner, "published", "Search Test Company", true, false);
    long bannedCompany = company(uniqueUser("banned-owner"), "banned", "Banned Search Company", true, true);
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    insertPosting(company, owner, "Security guard published " + token, "Visible published posting", "Ho Chi Minh, Vietnam",
        "FULL_TIME", "ONSITE", 8000000, 12000000, true, future, now.minus(2, ChronoUnit.DAYS));
    insertPosting(company, owner, "Paused posting " + token, "Should not appear", "Ho Chi Minh, Vietnam", "FULL_TIME",
        "ONSITE", 8000000, 12000000, true, future, now.minus(1, ChronoUnit.DAYS)) // will update status below
    ;
    jdbc.update("UPDATE job_postings SET status = 'PAUSED' WHERE id = (SELECT MAX(id) FROM job_postings WHERE company_id = ?)", company);
    insertPosting(company, owner, "Expired posting " + token, "Should not appear", "Ho Chi Minh, Vietnam", "FULL_TIME",
        "ONSITE", 8000000, 12000000, true, now.minus(1, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS)) // expired deadline
    ;
    insertPosting(bannedCompany, uniqueUser("banned-company-owner"), "Banned company posting " + token, "Should not appear",
        "Ho Chi Minh, Vietnam", "FULL_TIME", "ONSITE", 8000000, 12000000, true, future, now.minus(1, ChronoUnit.DAYS));

    http.perform(get("/api/jobs/search").param("keyword", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].content.title").value("Security guard published " + token));
  }

  @Test
  void filtersNarrowToMatchingPosting() throws Exception {
    String token = "FILTER-" + System.nanoTime();
    long owner = uniqueUser("filter");
    long company = company(owner, "filters", "GuardWork Filter Company", true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    insertPosting(company, owner, "Java backend engineer " + token, "Experience with backend3 APIs " + token, "Ha Noi, Vietnam",
        "FULL_TIME", "REMOTE", 10000000, 20000000, true, now.plus(20, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS));
    insertPosting(company, owner, "Security guard " + token, "No backend3 experience", "Ho Chi Minh, Vietnam", "CONTRACT",
        "ONSITE", 5000000, 8000000, true, now.plus(5, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

    http.perform(get("/api/jobs/search")
            .param("keyword", token)
            .param("location", "Ha Noi")
            .param("experience", token)
            .param("employmentType", "full_time")
            .param("workArrangement", "remote")
            .param("salaryMin", "12000000")
            .param("salaryMax", "18000000")
            .param("deadline", now.plus(25, ChronoUnit.DAYS).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].content.title").value("Java backend engineer " + token));

    // Inverted salary range is swapped by the service.
    http.perform(get("/api/jobs/search").param("keyword", token).param("salaryMin", "18000000").param("salaryMax", "12000000").param("deadline", now.plus(25, ChronoUnit.DAYS).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].content.title").value("Java backend engineer " + token));
  }

  @Test
  void paginationAndSorting() throws Exception {
    String token = "PAGING-" + System.nanoTime();
    long owner = uniqueUser("paging");
    long company = company(owner, "paging", "Paging Search Company", true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    for (int i = 1; i <= 6; i++) {
      insertPosting(company, owner, "Paging job " + i + " " + token, "Paging description " + token, "Ho Chi Minh, Vietnam",
          "FULL_TIME", "ONSITE", 8000000, 12000000, true, now.plus(30, ChronoUnit.DAYS),
          now.minus(i, ChronoUnit.DAYS));
    }

    http.perform(get("/api/jobs/search").param("keyword", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pageSize").value(5))
        .andExpect(jsonPath("$.data.pageNumber").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(6))
        .andExpect(jsonPath("$.data.content.length()").value(5))
        .andExpect(jsonPath("$.data.content[0].content.title").value("Paging job 1 " + token));

    http.perform(get("/api/jobs/search").param("keyword", token).param("page", "1").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pageNumber").value(1))
        .andExpect(jsonPath("$.data.pageSize").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].content.title").value("Paging job 3 " + token));
  }

  @Test
  void sizeIsBoundedToMaximum() throws Exception {
    String token = "BOUNDED-" + System.nanoTime();
    long owner = uniqueUser("bounded");
    long company = company(owner, "bounded", "Bounded Search Company", true, false);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    insertPosting(company, owner, "Bounded posting " + token, "Bounded " + token, "Ho Chi Minh, Vietnam", "FULL_TIME",
        "ONSITE", 8000000, 12000000, true, now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

    http.perform(get("/api/jobs/search").param("keyword", token).param("size", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pageSize").value(20))
        .andExpect(jsonPath("$.data.content.length()").value(1));
  }

  @Test
  void invalidInputsReturnBadRequest() throws Exception {
    http.perform(get("/api/jobs/search").param("employmentType", "NOT_A_TYPE"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/jobs/search").param("workArrangement", "REMOTEISH"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/jobs/search").param("deadline", "not-a-date"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anonymousSearchSetsNoStoreCacheControl() throws Exception {
    http.perform(get("/api/jobs/search"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
