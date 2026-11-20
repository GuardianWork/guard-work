package com.guardwork.backend.aihealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.job.service.JdScanAdapter;
import com.guardwork.backend.job.service.JdScanSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiHealthServiceTest {
  HttpServer server;
  java.util.concurrent.ExecutorService executor;
  final AtomicInteger requests = new AtomicInteger();
  final CountDownLatch release = new CountDownLatch(1);
  volatile String response;
  volatile int responseStatus = 200;
  volatile boolean delay;
  volatile boolean delayBody;
  volatile String method;
  volatile String key;

  @BeforeEach void setup() throws Exception {
    response = "{\"status\":\"ready\",\"mode\":\"crawler\",\"profile\":\"test-v1\",\"legalSnapshot\":\"labor-test-v1\"}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/health", exchange -> {
      requests.incrementAndGet();
      method = exchange.getRequestMethod();
      key = exchange.getRequestHeaders().getFirst("X-GuardWork-AI-Key");
      try {
        if (delay) release.await(5, TimeUnit.SECONDS);
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, body.length);
        if (delayBody) release.await(5, TimeUnit.SECONDS);
        exchange.getResponseBody().write(body);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  @AfterEach void cleanup() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  AiHealthService service(boolean enabled, String mode) {
    var settings = new JdScanSettings(enabled, mode, "test-v1", "labor-test-v1",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/", "test-shared-key-only");
    return new AiHealthService(settings, new JdScanAdapter(settings, new ObjectMapper()));
  }

  @Test void readyChecksOnlyTheHealthRouteAndReturnsAllowlistedConfiguration() {
    response = response.replace("}", ",\"secret\":\"must-not-be-forwarded\"}");
    var result = service(true, "crawler").check();
    assertThat(result).isEqualTo(new AiHealthService.View(AiHealthService.Status.READY,
        "crawler", "test-v1", "labor-test-v1"));
    assertThat(requests).hasValue(1);
    assertThat(method).isEqualTo("GET");
    assertThat(key).isNull();
  }

  @Test void matchingStubIsReadyButExplicitlyIdentifiedAsStub() {
    response = response.replace("crawler", "stub");
    var result = service(true, "stub").check();
    assertThat(result.status()).isEqualTo(AiHealthService.Status.READY);
    assertThat(result.mode()).isEqualTo("stub");
  }

  @Test void disabledDoesNotContactTheAdapter() {
    assertThat(service(false, "crawler").check().status()).isEqualTo(AiHealthService.Status.DISABLED);
    assertThat(requests).hasValue(0);
  }

  @Test void eachAnalysisIdentityMismatchIsRejected() {
    String healthy = response;
    for (String value : new String[] {"crawler", "test-v1", "labor-test-v1"}) {
      response = healthy.replace("\"" + value + "\"", "\"different\"");
      assertThat(service(true, "crawler").check().status())
          .as(value).isEqualTo(AiHealthService.Status.CONFIGURATION_MISMATCH);
    }
  }

  @Test void malformedIncompleteAndOversizedResponsesAreUnavailable() {
    String healthy = response;
    for (String body : new String[] {"", "not json", "null", "[]", "{}", "x".repeat(4097),
        healthy + " trailing garbage", healthy + " {}",
        healthy.replace("\"ready\"", "\"starting\""),
        healthy.replace("\"crawler\"", "null"), healthy.replace("\"test-v1\"", "23"),
        healthy.replace("\"labor-test-v1\"", "\"\"")}) {
      response = body;
      assertThat(service(true, "crawler").check().status()).isEqualTo(AiHealthService.Status.UNAVAILABLE);
    }
  }

  @Test void upstreamErrorsAndRedirectsAreUnavailable() {
    for (int code : new int[] {302, 401, 500, 503}) {
      responseStatus = code;
      assertThat(service(true, "crawler").check().status()).isEqualTo(AiHealthService.Status.UNAVAILABLE);
    }
  }

  @Test void stoppedAdapterIsUnavailable() {
    var health = service(true, "crawler");
    server.stop(0);
    assertThat(health.check().status()).isEqualTo(AiHealthService.Status.UNAVAILABLE);
  }

  @Test void slowAdapterTimesOutWithoutAnAnalysisCall() {
    delay = true;
    try {
      assertThat(service(true, "crawler").check().status()).isEqualTo(AiHealthService.Status.UNAVAILABLE);
      assertThat(requests).hasValue(1);
    } finally {
      release.countDown();
    }
  }

  @Test void stalledResponseBodyAlsoTimesOut() {
    delayBody = true;
    try {
      assertThat(service(true, "crawler").check().status()).isEqualTo(AiHealthService.Status.UNAVAILABLE);
      assertThat(requests).hasValue(1);
    } finally {
      release.countDown();
    }
  }
}
