package com.guardwork.backend.invitation.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.invitation.model.DeliveryStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResendCompanyInvitationMailerTest {
  @Test
  void providerRequestUsesPrivateFragmentVietnamExpiryAndAcknowledgesOnlyAcceptance()
      throws Exception {
    AtomicReference<String> body = new AtomicReference<>(), authorization = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] result = "{\"id\":\"stub-message-id\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, result.length);
          exchange.getResponseBody().write(result);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          adapter(
              server,
              "https://app.guardwork.vn/invitation",
              "https://app.guardwork.vn",
              Duration.ofSeconds(2));
      assertThat(
              adapter.send(
                  "recruiter@example.vn",
                  "Ánh Dương",
                  Instant.parse("2026-10-15T16:59:59Z"),
                  "controlled-private-secret"))
          .isEqualTo(DeliveryStatus.PROVIDER_ACCEPTED);
      var sent = new ObjectMapper().readTree(body.get());
      assertThat(authorization.get()).isEqualTo("Bearer controlled-stub-credential");
      assertThat(sent.path("to").get(0).asText()).isEqualTo("recruiter@example.vn");
      assertThat(sent.path("text").asText())
          .contains(
              "Ánh Dương",
              "RECRUITER",
              "15/10/2026 23:59",
              "https://app.guardwork.vn/invitation#secret=controlled-private-secret");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void explicitlyAllowlistedLocalhostHttpUrlsPreservePrivateFragmentAndMatchExactPort()
      throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] result = "{\"id\":\"stub-message-id\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, result.length);
          exchange.getResponseBody().write(result);
          exchange.close();
        });
    server.start();
    try {
      for (String[] configuration :
          new String[][] {
            {"http://localhost:5173/invitation", "https://app.guardwork.vn, http://localhost:5173"},
            {"http://localhost:3000/invitation", "http://localhost:3000/"},
            {"http://localhost/invitation", "http://localhost:80"},
            {"http://localhost:80/invitation", "http://localhost"}
          }) {
        assertThat(
                adapter(server, configuration[0], configuration[1], Duration.ofSeconds(2))
                    .send("recipient@example.vn", "Ánh", Instant.now(), "controlled-private-secret"))
            .as("allowlisted URL %s", configuration[0])
            .isEqualTo(DeliveryStatus.PROVIDER_ACCEPTED);
        assertThat(new ObjectMapper().readTree(body.get()).path("text").asText())
            .contains(configuration[0] + "#secret=controlled-private-secret");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void localhostExceptionRejectsRemoteHttpLookalikesAndNonmatchingOriginsBeforeSending()
      throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(400, -1);
          exchange.close();
        });
    server.start();
    try {
      for (String[] configuration :
          new String[][] {
            {"http://app.guardwork.vn/invitation", "http://app.guardwork.vn"},
            {"http://localhost.evil.example/invitation", "http://localhost.evil.example"},
            {"http://sub.localhost/invitation", "http://sub.localhost"},
            {"http://localhost./invitation", "http://localhost."},
            {"http://127.0.0.1:5173/invitation", "http://127.0.0.1:5173"},
            {"http://[::1]:5173/invitation", "http://[::1]:5173"},
            {"http://localhost:5173/invitation", "http://localhost:3000"},
            {"http://localhost:5173/invitation", "https://localhost:5173"},
            {"http://localhost:443/invitation", "http://localhost"},
            {"http://localhost:5173/invitation", "http://localhost:5173/invitation"},
            {"http://localhost:5173/invitation", ""},
            {"http://user@localhost:5173/invitation", "http://localhost:5173"},
            {"http://localhost:5173/invitation?secret=exposed", "http://localhost:5173"},
            {"http://localhost:5173/invitation#secret=exposed", "http://localhost:5173"},
            {"http://localhost:0/invitation", "http://localhost:0"},
            {"http://localhost:65536/invitation", "http://localhost:65536"}
          }) {
        assertThat(
                adapter(server, configuration[0], configuration[1], Duration.ofSeconds(1))
                    .send("recipient@example.vn", "Ánh", Instant.now(), "private"))
            .as("reject URL %s with allowed origin %s", configuration[0], configuration[1])
            .isEqualTo(DeliveryStatus.FAILED);
      }
      assertThat(calls.get()).isZero();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unallowlistedInsecureOrSecretBearingFrontendUrlsNeverContactProvider() throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(400, -1);
          exchange.close();
        });
    server.start();
    try {
      for (String url :
          new String[] {
            "http://app.guardwork.vn/invitation",
            "https://evil.example/invitation",
            "https://app.guardwork.vn/invitation?secret=exposed",
            "https://app.guardwork.vn/invitation#secret=exposed",
            "https://user@app.guardwork.vn/invitation"
          }) {
        assertThat(
                adapter(server, url, "https://app.guardwork.vn", Duration.ofSeconds(1))
                    .send("recipient@example.vn", "Ánh", Instant.now(), "private"))
            .isEqualTo(DeliveryStatus.FAILED);
      }
      assertThat(calls.get()).isZero();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void explicitProviderRejectionIsFailedAndTimeoutIsUnknownWithoutRetry() throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          int call = calls.incrementAndGet();
          if (call > 1) {
            try {
              Thread.sleep(400);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          exchange.sendResponseHeaders(422, -1);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          adapter(
              server,
              "https://app.guardwork.vn/invitation",
              "https://app.guardwork.vn",
              Duration.ofSeconds(2));
      assertThat(adapter.send("recipient@example.vn", "Ánh", Instant.now(), "one"))
          .isEqualTo(DeliveryStatus.FAILED);
      adapter =
          adapter(
              server,
              "https://app.guardwork.vn/invitation",
              "https://app.guardwork.vn",
              Duration.ofMillis(100));
      assertThat(adapter.send("recipient@example.vn", "Ánh", Instant.now(), "two"))
          .isEqualTo(DeliveryStatus.UNKNOWN);
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  private ResendCompanyInvitationMailer adapter(
      HttpServer server, String baseUrl, String allowedOrigins, Duration timeout) {
    return new ResendCompanyInvitationMailer(
        new ObjectMapper(),
        "invites@guardwork.vn",
        "controlled-stub-credential",
        baseUrl,
        allowedOrigins,
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails"),
        timeout);
  }
}
