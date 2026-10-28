package com.guardwork.backend.invitation;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    properties = {
      "company.membership.notifications.worker-enabled=true",
      "company.membership.notifications.poll-ms=50"
    })
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(RecipientInvitationHttpTest.ControlledDelivery.class)
class MembershipNotificationWorkerHttpTest {
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  @Autowired RecipientInvitationHttpTest.MutableClock clock;
  @Autowired com.guardwork.backend.notification.service.NotificationIntentService intents;
  @Autowired com.guardwork.backend.company.repository.CompanyRepository companies;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  MockMvc http;

  @BeforeEach
  void setup() {
    clock.now = java.time.Instant.parse("2026-10-08T16:59:59Z");
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void scopedWorkerAutomaticallyDeliversDurableDueEventAfterCommit() throws Exception {
    String owner = account();
    long company = createdCompany(owner), recipient = userId(owner);
    var due = clock.now.plusSeconds(600);
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    tx.executeWithoutResult(
        status ->
            intents.record(
                recipient, "MEMBERSHIP_REMOVED", companies.findById(company).orElseThrow(), due));
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.totalElements").value(0));
    clock.now = due;
    long end = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    long count = 0;
    do {
      var response =
          http.perform(get("/api/me/notifications").header("Authorization", owner))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      count =
          mapper
              .readTree(response.getContentAsString())
              .path("data")
              .path("totalElements")
              .asLong();
      if (count == 1) break;
      Thread.sleep(25);
    } while (System.nanoTime() < end);
    assertThat(count).isEqualTo(1);
    http.perform(get("/api/me/notifications").header("Authorization", owner))
        .andExpect(jsonPath("$.data.content[0].companyName").value("Công ty Giải Pháp Ánh Dương"));
  }

  long userId(String token) throws Exception {
    String body =
        http.perform(get("/api/auth/me").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  long createdCompany(String token) throws Exception {
    String body =
        http.perform(
                completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString()))
                    .header("Authorization", token)

                    )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body).path("data").path("id").asLong();
  }

  String account() throws Exception {
    String name = "user_" + UUID.randomUUID().toString().replace("-", "");
    return account(name + "@example.vn");
  }

  String account(String email) throws Exception {
    String name = "user_" + UUID.randomUUID().toString().replace("-", "");
    http.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "firstName",
                            "Ánh",
                            "lastName",
                            "Nguyễn",
                            "username",
                            name,
                            "email",
                            email,
                            "password",
                            "Password123"))))
        .andExpect(status().isCreated());
    database.update("UPDATE users SET recruiting_email_verification_required=FALSE WHERE email=?", email);
    String response =
        http.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("usernameOrEmail", email, "password", "Password123"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
  }

  String company(String taxCode) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "name",
            "Công ty Giải Pháp Ánh Dương",
            "taxCode",
            taxCode,
            "email",
            taxCode + "@company.vn",
            "registrationCertificateUrl",
            "https://storage.guardwork.vn/giấy-phép.pdf"));
  }
}
