package com.guardwork.backend.legalchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"jobs.ai.enabled=true", "jobs.ai.worker-enabled=false",
    "jobs.ai.mode=crawler", "jobs.ai.legal-snapshot=test-labor-v1"})
class LegalChatHttpTest {
  static final AtomicReference<JsonNode> REQUEST = new AtomicReference<>();
  static final AtomicReference<String> RESPONSE = new AtomicReference<>();
  static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);
  static final HttpServer ADAPTER = adapter();
  static final String PATH = "/api/candidates/me/legal-chat";
  @Autowired WebApplicationContext context;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate database;
  MockMvc http;

  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("jobs.ai.adapter-url", () -> "http://127.0.0.1:" + ADAPTER.getAddress().getPort());
  }

  static HttpServer adapter() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/chat", exchange -> {
        var json = new ObjectMapper();
        JsonNode request = json.readTree(exchange.getRequestBody());
        REQUEST.set(request);
        String result = RESPONSE.get();
        if (result == null) {
          var report = json.createObjectNode();
          report.put("schemaVersion", "1");
          for (String field : new String[] {"mode", "profile", "legalSnapshot", "asOfDate"})
            report.set(field, request.path(field));
          report.put("model", "test-provider"); report.put("status", "ANSWERED");
          report.put("answer", "A grounded answer"); report.putArray("warnings").add("Verify applicability");
          report.putObject("usage").put("available", false);
          var citation = report.putArray("citations").addObject();
          citation.put("nodeId", "doc:139264:art:25"); citation.put("quote", "Source quotation");
          citation.put("label", "Điều 25"); citation.put("documentNumber", "45/2019/QH14");
          citation.put("effectiveStatus", ""); citation.put("quoteGrounded", true);
          citation.put("applicability", "UNVERIFIED"); result = json.writeValueAsString(report);
        }
        byte[] body = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(STATUS.get(), body.length);
        exchange.getResponseBody().write(body); exchange.close();
      });
      server.start(); return server;
    } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }

  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    RESPONSE.set(null); STATUS.set(200); REQUEST.set(null);
  }

  @Test void userReceivesGroundedAnswerAndProviderReceivesNoAccountData() throws Exception {
    String token = account();
    http.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"  Thử việc?  \"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("crawler"))
        .andExpect(jsonPath("$.data.status").value("ANSWERED"))
        .andExpect(jsonPath("$.data.citations[0].quoteGrounded").value(true));
    assertThat(REQUEST.get().path("question").asText()).isEqualTo("Thử việc?");
    assertThat(REQUEST.get().size()).isEqualTo(5);
    assertThat(REQUEST.get().has("userId")).isFalse();
  }

  @Test void anonymousStaffAndDeactivatedAccountsCannotSpendProviderCalls() throws Exception {
    http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Test\"}"))
        .andExpect(status().isUnauthorized());
    String token = account();
    database.update("UPDATE users SET self_service_state='DEACTIVATED' WHERE username=?", username);
    http.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"Test\"}")).andExpect(status().isUnauthorized());
    token = account();
    database.update("UPDATE users SET role='STAFF' WHERE username=?", username);
    String response = http.perform(post("/api/auth/login").header("X-Guardian-Portal", "staff")
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
            "usernameOrEmail", username, "password", "Password123"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String staff = "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    http.perform(post(PATH).header("Authorization", staff).contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"Test\"}")).andExpect(status().isForbidden());
    assertThat(REQUEST.get()).isNull();
  }

  @Test void emptyOversizedAndNonTextQuestionsFailBeforeTheAdapter() throws Exception {
    String token = account();
    for (String body : new String[] {"[]", "{", "{}", "{\"question\":23}", "{\"question\":\"  \"}",
        mapper.writeValueAsString(Map.of("question", "x".repeat(2001)))}) {
      http.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
          .content(body)).andExpect(status().isBadRequest());
    }
    assertThat(REQUEST.get()).isNull();
  }

  @Test void malformedOrWrongModeOutputIsAnErrorNeverASampleAnswer() throws Exception {
    String token = account();
    for (String result : new String[] {"not JSON", "{\"mode\":\"stub\",\"answer\":\"Sample\"}"}) {
      RESPONSE.set(result);
      http.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
          .content("{\"question\":\"Test\"}")).andExpect(status().isServiceUnavailable());
    }
    STATUS.set(429);
    http.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"Test\"}")).andExpect(status().isTooManyRequests());
  }

  String username;
  String account() throws Exception {
    username = "chat_" + UUID.randomUUID().toString().replace("-", "");
    http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", username,
            "email", username + "@example.vn", "password", "Password123")))).andExpect(status().isCreated());
    String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", username, "password", "Password123"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
  }
}
