package com.guardwork.backend.auth;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {
}
