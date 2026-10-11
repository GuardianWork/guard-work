package com.guardwork.backend.invitation.dto;

/** Never include the mailbox credential in diagnostic string representations. */
public record RecipientInvitationRequest(String secret, Boolean confirm) {
  @Override
  public String toString() {
    return "RecipientInvitationRequest[secret=REDACTED]";
  }
}
