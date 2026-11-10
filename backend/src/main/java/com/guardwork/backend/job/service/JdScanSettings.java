package com.guardwork.backend.job.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record JdScanSettings(
    @Value("${jobs.ai.enabled:false}") boolean enabled,
    @Value("${jobs.ai.mode:stub}") String mode,
    @Value("${jobs.ai.profile:jd-local-v1}") String profile,
    @Value("${jobs.ai.legal-snapshot:stub-v1}") String legalSnapshot,
    @Value("${jobs.ai.adapter-url:http://127.0.0.1:8011}") String adapterUrl,
    @Value("${jobs.ai.shared-key:local-development-only}") String sharedKey) {
  public JdScanSettings {
    if (!java.util.Set.of("stub", "crawler").contains(mode)
        || !profile.matches("[A-Za-z0-9._-]{1,100}")
        || !legalSnapshot.matches("[A-Za-z0-9._-]{1,100}"))
      throw new IllegalArgumentException("Invalid JD scan configuration");
    if (enabled && (sharedKey.length() < 16
        || (mode.equals("crawler") && legalSnapshot.equals("stub-v1"))))
      throw new IllegalArgumentException("JD scanning requires a key and an explicit real corpus snapshot");
  }
}
