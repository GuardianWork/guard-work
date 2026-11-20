package com.guardwork.backend.aihealth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.job.service.*;
import com.guardwork.backend.legalchat.LegalChatService;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class AiServiceHttpsTest {
  static final String KEY="synthetic-ai-key-only";
  static final ObjectMapper JSON=new ObjectMapper();
  @TempDir static Path directory;
  static SSLContext original;
  static SSLContext trusted;
  HttpsServer server;
  String base;
  volatile boolean redirect;
  final AtomicInteger leaked=new AtomicInteger();
  final Map<String,String> requests=new ConcurrentHashMap<>();

  @BeforeAll static void certificate() throws Exception {
    original=SSLContext.getDefault();
    Path store=directory.resolve("fixture.p12");
    var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
        "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","1",
        "-storetype","PKCS12","-keystore",store.toString(),"-storepass","synthetic-test-only",
        "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-noprompt")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    if (!keytool.waitFor(20,TimeUnit.SECONDS)) {
      keytool.destroyForcibly(); keytool.waitFor(); throw new IllegalStateException("Fixture certificate timed out");
    }
    assertThat(keytool.exitValue()).isZero();
    KeyStore keys=KeyStore.getInstance("PKCS12");
    try (var input=Files.newInputStream(store)) { keys.load(input,"synthetic-test-only".toCharArray()); }
    var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys,"synthetic-test-only".toCharArray());
    var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
    trusted=SSLContext.getInstance("TLS"); trusted.init(km.getKeyManagers(),tm.getTrustManagers(),null);
  }

  @BeforeEach void start() throws Exception {
    SSLContext.setDefault(trusted);
    server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.setHttpsConfigurator(new HttpsConfigurator(trusted));
    server.createContext("/",this::respond);
    server.start(); base="https://localhost:"+server.getAddress().getPort()+"/ai/v1/";
  }
  @AfterEach void stop() { server.stop(0); SSLContext.setDefault(original); }

  void respond(HttpExchange exchange) throws java.io.IOException {
    try {
      String route=exchange.getRequestURI().getPath();
      if (route.equals("/unexpected")) leaked.incrementAndGet();
      if (redirect) {
        exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/unexpected");
        exchange.sendResponseHeaders(307,-1); return;
      }
      requests.put(route,exchange.getRequestMethod());
      if (!route.endsWith("/health") && !KEY.equals(exchange.getRequestHeaders().getFirst("X-GuardWork-AI-Key"))) {
        exchange.sendResponseHeaders(401,-1); return;
      }
      Map<String,Object> report=new LinkedHashMap<>();
      report.put("schemaVersion","1"); report.put("mode","crawler");
      report.put("profile","tls-test"); report.put("legalSnapshot","fixture-corpus");
      if (route.endsWith("/health")) report.put("status","ready");
      else {
        var input=JSON.readTree(exchange.getRequestBody());
        report.put("asOfDate",input.path("asOfDate").asText());
        report.put("usage",Map.of("available",false)); report.put("warnings",List.of());
        if (route.endsWith("/analyze")) { report.put("outcome","NO_RISKS_DETECTED"); report.put("findings",List.of()); }
        else if (route.endsWith("/chat")) {
          report.put("model","fixture-model"); report.put("status","INSUFFICIENT_CONTEXT");
          report.put("answer","Not enough fixture evidence"); report.put("citations",List.of());
        } else { exchange.sendResponseHeaders(404,-1); return; }
      }
      byte[] body=JSON.writeValueAsBytes(report);
      exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
    } finally { exchange.close(); }
  }

  JdScanSettings settings() { return new JdScanSettings(true,"crawler","tls-test","fixture-corpus",base,KEY); }
  JdScanQueue.Claim claim() {
    return new JdScanQueue.Claim(1L,UUID.randomUUID(),"Synthetic job description","crawler","tls-test","fixture-corpus",LocalDate.of(2026,11,16));
  }

  @Test void receivesValidatedResultsOverHttpsWithConfiguredPathAndKeys() throws Exception {
    var adapter=new JdScanAdapter(settings(),JSON);
    assertThat(adapter.health().path("status").asText()).isEqualTo("ready");
    assertThat(JSON.readTree(adapter.analyze(claim())).path("outcome").asText()).isEqualTo("NO_RISKS_DETECTED");
    assertThat(new LegalChatService(settings(),JSON,Clock.systemUTC()).chat(Map.of("question","Fixture question")))
        .containsEntry("status","INSUFFICIENT_CONTEXT");
    assertThat(requests).containsExactlyInAnyOrderEntriesOf(Map.of("/ai/v1/health","GET","/ai/v1/analyze","POST",
        "/ai/v1/chat","POST"));
  }

  @Test void redirectsCannotForwardConfiguredCredentials() {
    redirect=true;
    var adapter=new JdScanAdapter(settings(),JSON);
    assertThatThrownBy(adapter::health).isInstanceOf(JdScanAdapter.Failure.class);
    assertThatThrownBy(() -> adapter.analyze(claim())).isInstanceOf(JdScanAdapter.Failure.class);
    assertThatThrownBy(() -> new LegalChatService(settings(),JSON,Clock.systemUTC()).chat(Map.of("question","Fixture")))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(leaked).hasValue(0);
  }

  @Test void untrustedTlsCertificatesAreRejected() {
    SSLContext.setDefault(original);
    assertThatThrownBy(() -> new JdScanAdapter(settings(),JSON).health()).isInstanceOf(JdScanAdapter.Failure.class);
  }
}
