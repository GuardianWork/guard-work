package com.guardwork.backend.invitation.mail;

import com.guardwork.backend.invitation.model.DeliveryStatus;
import java.time.Instant;

/** Private external delivery boundary. Never serialize or log the secret-bearing arguments. */
public interface CompanyInvitationMailer {
  DeliveryStatus send(String recipientEmail, String companyName, Instant expiresAt, String secret);
}
