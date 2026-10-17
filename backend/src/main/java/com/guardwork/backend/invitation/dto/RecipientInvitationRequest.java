package com.guardwork.backend.invitation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Never include the mailbox credential in diagnostic string representations. */
public record RecipientInvitationRequest(
    @NotBlank @Size(max = 128) String secret,
    @NotNull(groups = Acceptance.class) @AssertTrue(groups = Acceptance.class) Boolean confirm) {
  public interface Acceptance {}

  @Override
  public String toString() {
    return "RecipientInvitationRequest[secret=REDACTED]";
  }
}
