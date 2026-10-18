package com.guardwork.backend.onboarding.delivery;

import java.util.UUID;

/** External delivery seam. Implementations must never log secret-bearing arguments. */
public interface OnboardingDelivery {
    boolean registrationEmail(String recipient, UUID attemptId, String secret);
    boolean memberEmail(String recipient, String secret);
    boolean sms(String phone, String code);
    boolean decisionEmail(String recipient, String company, String outcome, String reason, UUID decisionId);
}
