package com.guardwork.backend.company.dto;

import java.util.UUID;

public record LegalDetails(String workAddress, String submitterPosition, String representation,
                           Boolean declarationAccepted, UUID documentId) {}
