package com.guardwork.backend.staff.service;

import com.guardwork.backend.logging.DiagnosticLog;
import com.guardwork.backend.logging.DiagnosticEvent;

import com.guardwork.backend.staff.repository.AccountBanRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="accounts.expiry.worker-enabled",havingValue="true",matchIfMissing=true)
public class AccountBanExpiryWorker {
  private static final Logger log=LoggerFactory.getLogger(AccountBanExpiryWorker.class);
  private final AccountBanRepository bans;
  private final AccountEnforcementService enforcement;
  private final Clock clock;
  public AccountBanExpiryWorker(AccountBanRepository bans,AccountEnforcementService enforcement,Clock clock) {
    this.bans=bans;this.enforcement=enforcement;this.clock=clock;
  }
  @Scheduled(fixedDelayString="${accounts.expiry.poll-ms:60000}",initialDelayString="${accounts.expiry.poll-ms:60000}")
  public void expireDue() {
    for(var ban:bans.findDue(clock.instant(),PageRequest.of(0,100))) {
      try { enforcement.expire(ban.getId()); }
      catch (RuntimeException failure) { DiagnosticLog.error(log,DiagnosticEvent.UNEXPECTED_FAILURE,failure); log.warn("event=account_ban_expiry outcome=retry_required banId={}",ban.getId()); }
    }
  }
}
