package com.guardwork.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserDto> register(@RequestBody RegisterUserRequest request) {
        UserDto user = authService.register(request);
        return ApiResponse.ok("User registered successfully", user);
    }

    @PostMapping("/login")
    public ApiResponse<TokenPairResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ipAddress = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        TokenPairResponse response = authService.login(request, ipAddress, userAgent);
        return ApiResponse.ok("Login successful", response);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPairResponse> refresh(@RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String ipAddress = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        TokenPairResponse response = authService.refreshToken(request, ipAddress, userAgent);
        return ApiResponse.ok("Token refreshed successfully", response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        if (request != null && request.refreshToken() != null) {
            authService.logout(request.refreshToken());
        }
        return ApiResponse.ok("Logged out successfully", null);
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Authentication required");
        }
        CurrentUserResponse response = authService.getCurrentUser(principal.getId());
        return ApiResponse.ok("Current user retrieved successfully", response);
    }

    @PutMapping("/change-password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestBody ChangePasswordRequest request) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Authentication required");
        }
        authService.changePassword(principal.getId(), request);
        return ApiResponse.ok("Password changed successfully", null);
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.ok("If that email is registered, password reset instructions have been sent", null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok("Password reset successfully. Please log in with your new password.", null);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isBlank()) {
            return xfHeader.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}