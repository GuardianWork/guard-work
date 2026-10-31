package com.guardwork.backend.onboarding.delivery;

import java.util.UUID;

/** External delivery seam. Implementations must never log secret-bearing arguments. */
public interface OnboardingDelivery {
    boolean accountActionEmail(String recipient, String secret, String action);
    boolean memberEmail(String recipient, String secret);
    boolean decisionEmail(String recipient, String company, String outcome, String reason, UUID decisionId);
}
