package com.guardwork.backend.aihealth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.job.service.JdScanAdapter;
import com.guardwork.backend.job.service.JdScanSettings;
import com.guardwork.backend.legalchat.LegalChatService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;
import static org.assertj.core.api.Assertions.*;

class AiServiceConfigurationTest {
  @Test void clientsAcceptLocalPrivateAndHostedServiceLocations() {
    for (String url:List.of("http://127.0.0.1:8011", "http://[::1]:8011/",
        "http://ai-service:8011", "https://ai.example.test", "https://ai.example.test/inference/v1/")) {
      var settings=new JdScanSettings(true,"crawler","test-v1","labor-test",url,"synthetic-shared-key");
      var mapper=new ObjectMapper();
      boolean trustedPrivateNetwork=url.equals("http://ai-service:8011");
      assertThatCode(() -> new JdScanAdapter(settings,mapper,trustedPrivateNetwork)).as(url).doesNotThrowAnyException();
      assertThatCode(() -> new LegalChatService(settings,mapper,Clock.systemUTC(),trustedPrivateNetwork)).as(url).doesNotThrowAnyException();
    }
  }

  @Test void clientsRejectInvalidUrlsWithoutExposingEmbeddedSecrets() {
    for (String url:List.of("relative/path", "ftp://ai.example.test", "https:///missing-host",
        "https://user:fixture-secret@ai.example.test", "https://ai.example.test?key=fixture-secret",
        "https://ai.example.test#fixture-secret", "http://ai-service:0", "https://ai.example.test:65536")) {
      var settings=new JdScanSettings(true,"crawler","test-v1","labor-test",url,"synthetic-shared-key");
      var mapper=new ObjectMapper();
      assertThatThrownBy(() -> new JdScanAdapter(settings,mapper)).as(url)
          .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("fixture-secret");
      assertThatThrownBy(() -> new LegalChatService(settings,mapper,Clock.systemUTC())).as(url)
          .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("fixture-secret");
    }
  }

  @Test void nonLocalHttpIsRejectedWithoutAnExplicitPrivateNetworkOptIn() {
    var settings=new JdScanSettings(true,"crawler","test-v1","labor-test","http://public-ai.example.test","synthetic-shared-key");
    var mapper=new ObjectMapper();
    assertThatThrownBy(() -> new JdScanAdapter(settings,mapper)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LegalChatService(settings,mapper,Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void backendEnvOverridesSharedDefaultsAndRenderEnvironmentOverridesBoth(@TempDir Path directory) throws Exception {
    Path shared=directory.resolve("shared.env"), backend=directory.resolve("backend.env");
    Files.writeString(shared,"GUARDWORK_AI_ADAPTER_URL=http://127.0.0.1:8011\nGUARDWORK_AI_SHARED_KEY=shared-fixture\n");
    Files.writeString(backend,"GUARDWORK_AI_ADAPTER_URL=https://backend-ai.example.test/ai\nGUARDWORK_AI_SHARED_KEY=backend-fixture\n");
    String properties=Files.readString(Path.of("src/main/resources/application.properties"));
    // Exercise the shipped import order with isolated fixture files, never the developer's secrets.
    properties=properties.replace("optional:file:../ai/.env.local[.properties]",shared.toUri()+"[.properties]")
        .replace("optional:file:.env[.properties]",backend.toUri()+"[.properties]");
    Path config=directory.resolve("application.properties"); Files.writeString(config,properties);
    var environment=new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("fixture-config",
        Map.of("spring.config.location",config.toUri().toString())));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    assertThat(environment.getProperty("jobs.ai.adapter-url")).isEqualTo("https://backend-ai.example.test/ai");
    assertThat(environment.getProperty("jobs.ai.shared-key")).isEqualTo("backend-fixture");
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("render-environment",Map.of(
        "GUARDWORK_AI_ADAPTER_URL","https://render-ai.example.test",
        "GUARDWORK_AI_SHARED_KEY","render-fixture")));
    assertThat(environment.getProperty("jobs.ai.adapter-url")).isEqualTo("https://render-ai.example.test");
    assertThat(environment.getProperty("jobs.ai.shared-key")).isEqualTo("render-fixture");
  }
}
