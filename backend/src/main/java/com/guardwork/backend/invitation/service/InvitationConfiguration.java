package com.guardwork.backend.invitation.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InvitationConfiguration {
  @Bean
  public Clock companyMembershipClock() {
    return Clock.systemUTC();
  }
}
