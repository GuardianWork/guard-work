package com.guardwork.backend.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Recovers approved Company Membership and Job Posting notification intents. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "company.membership.notifications.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MembershipNotificationWorker {
  private final NotificationDispatcher dispatcher;

  public MembershipNotificationWorker(NotificationDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(
      fixedDelayString = "${company.membership.notifications.poll-ms:30000}",
      initialDelayString = "${company.membership.notifications.poll-ms:30000}")
  public void deliverDue() {
    dispatcher.dispatchDue();
  }
}
