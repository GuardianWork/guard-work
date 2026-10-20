package com.guardwork.backend.job.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="jobs.expiry.worker-enabled",havingValue="true",matchIfMissing=true)
public class JobExpiryWorker {
  private final JobExpiryService expiry;
  public JobExpiryWorker(JobExpiryService expiry){this.expiry=expiry;}
  @Scheduled(fixedDelayString="${jobs.expiry.poll-ms:60000}",initialDelayString="${jobs.expiry.poll-ms:60000}")
  public void expireDue(){expiry.expireDue();}
}
