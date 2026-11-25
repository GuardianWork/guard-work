package com.guardwork.backend.application.repository;

import java.util.UUID;

/** Candidate-owned destination and recipient, without submitted personal information. */
public interface ApplicationNotificationRecipient {
    Long getUserId();

    UUID getApplicationId();
}
