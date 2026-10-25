package com.guardwork.backend.auth;

public record ResetPasswordRequest(
        String token,
        String newPassword
) {
}
