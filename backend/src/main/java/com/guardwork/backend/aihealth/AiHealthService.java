package com.guardwork.backend.aihealth;

import com.guardwork.backend.job.service.JdScanAdapter;
import com.guardwork.backend.job.service.JdScanSettings;
import org.springframework.stereotype.Service;

@Service
public class AiHealthService {
  private final JdScanSettings settings;
  private final JdScanAdapter adapter;

  public AiHealthService(JdScanSettings settings, JdScanAdapter adapter) {
    this.settings = settings; this.adapter = adapter;
  }

  public enum Status { READY, DISABLED, UNAVAILABLE, CONFIGURATION_MISMATCH }

  public record View(Status status, String mode, String profile, String legalSnapshot) {}

  public View check() {
    if (!settings.enabled()) return view(Status.DISABLED);
    try {
      var health = adapter.health();
      if (health == null || !health.isObject() || !health.path("status").asText().equals("ready"))
        return view(Status.UNAVAILABLE);
      for (String field : new String[] {"mode", "profile", "legalSnapshot"}) {
        if (!health.path(field).isTextual() || health.path(field).asText().isBlank())
          return view(Status.UNAVAILABLE);
      }
      if (!settings.mode().equals(health.path("mode").asText())
          || !settings.profile().equals(health.path("profile").asText())
          || !settings.legalSnapshot().equals(health.path("legalSnapshot").asText()))
        return view(Status.CONFIGURATION_MISMATCH);
      return view(Status.READY);
    } catch (JdScanAdapter.Failure unavailable) {
      return view(Status.UNAVAILABLE);
    }
  }

  private View view(Status status) {
    // Allowlist configured identity; never forward raw adapter output or credentials.
    return new View(status, settings.mode(), settings.profile(), settings.legalSnapshot());
  }
}
