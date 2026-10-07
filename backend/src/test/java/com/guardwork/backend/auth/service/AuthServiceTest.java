package com.guardwork.backend.auth.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.auth.AuthService;
import com.guardwork.backend.auth.ChangePasswordRequest;
import com.guardwork.backend.auth.LoginRequest;
import com.guardwork.backend.auth.RefreshTokenRequest;
import com.guardwork.backend.auth.RegisterUserRequest;
import com.guardwork.backend.auth.TokenPairResponse;
import com.guardwork.backend.auth.UserDto;
import com.guardwork.backend.auth.model.RefreshToken;
import com.guardwork.backend.auth.repository.PasswordResetTokenRepository;
import com.guardwork.backend.auth.repository.RefreshTokenRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    private JwtService jwtService;
    private BCryptPasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(
                "unit_test_jwt_key_that_is_long_enough_32_bytes_xyz",
                60000,
                600000
        );
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordResetTokenRepository,
                jwtService,
                passwordEncoder
        );
    }

    private User createSampleUser(Long id, String username, String email, String password, String role, String status) {
        User user = new User();
        user.setId(id);
        user.setFirstName("Nguyễn");
        user.setLastName("Văn An");
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setStatus(status);
        user.setEmailVerified(true);
        return user;
    }

    @Test
    void register_ValidRequest_Success() {
        RegisterUserRequest request = new RegisterUserRequest(
                "Nguyễn", "An", "an_nguyen", "an@example.com", "Password123"
        );
        when(userRepository.findByUsername("an_nguyen")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("an@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(10L);
            return u;
        });

        UserDto userDto = authService.register(request);

        assertThat(userDto.id()).isEqualTo(10L);
        assertThat(userDto.username()).isEqualTo("an_nguyen");
        assertThat(userDto.role()).isEqualTo("USER");
    }

    @Test
    void register_WeakPassword_ThrowsBadRequest() {
        RegisterUserRequest request = new RegisterUserRequest(
                "Nguyễn", "An", "an_nguyen", "an@example.com", "weak"
        );

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_DuplicateUsername_ThrowsConflict() {
        RegisterUserRequest request = new RegisterUserRequest(
                "Nguyễn", "An", "existing_user", "an@example.com", "Password123"
        );
        when(userRepository.findByUsername("existing_user"))
                .thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_ValidCredentials_Success() {
        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "Password123", "USER", "ACTIVE");
        when(userRepository.findByUsername("an_nguyen")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("an_nguyen", "Password123");
        TokenPairResponse response = authService.login(request, "127.0.0.1", "JUnit");

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("an_nguyen");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void login_InvalidPassword_ThrowsUnauthorized() {
        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "Password123", "USER", "ACTIVE");
        when(userRepository.findByUsername("an_nguyen")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("an_nguyen", "WrongPassword");

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_SuspendedAccount_ThrowsForbidden() {
        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "Password123", "USER", "SUSPENDED");
        when(userRepository.findByUsername("an_nguyen")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("an_nguyen", "Password123");

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refreshToken_ValidToken_RotatesAndReturnsNewPair() {
        String rawToken = "old-refresh-token-uuid";
        String tokenHash = jwtService.hashToken(rawToken);

        RefreshToken token = new RefreshToken();
        token.setId(5L);
        token.setUserId(1L);
        token.setTokenHash(tokenHash);
        token.setFamilyId("family-123");
        token.setRevoked(false);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "Password123", "USER", "ACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        TokenPairResponse response = authService.refreshToken(new RefreshTokenRequest(rawToken), "127.0.0.1", "JUnit");

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotEqualTo(rawToken);

        // Verify old token was revoked
        verify(refreshTokenRepository).revokeToken(eq(5L), any(Instant.class));
        // Verify new token was saved
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshToken_RevokedToken_TriggersFamilyRevocation() {
        String rawToken = "already-revoked-token";
        String tokenHash = jwtService.hashToken(rawToken);

        RefreshToken token = new RefreshToken();
        token.setId(5L);
        token.setFamilyId("family-123");
        token.setRevoked(true); // Already revoked!

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest(rawToken), "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Entire family revoked!
        verify(refreshTokenRepository).revokeFamily(eq("family-123"), any(Instant.class));
    }

    @Test
    void changePassword_ValidCurrentPassword_Success() {
        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "OldPassword123", "USER", "ACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ChangePasswordRequest request = new ChangePasswordRequest("OldPassword123", "NewPassword456");
        authService.changePassword(1L, request);

        verify(userRepository).updatePassword(eq(1L), any());
        verify(refreshTokenRepository).revokeAllForUser(eq(1L), any(Instant.class));
    }

    @Test
    void changePassword_WrongCurrentPassword_ThrowsBadRequest() {
        User user = createSampleUser(1L, "an_nguyen", "an@example.com", "OldPassword123", "USER", "ACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ChangePasswordRequest request = new ChangePasswordRequest("WrongCurrent123", "NewPassword456");

        assertThatThrownBy(() -> authService.changePassword(1L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
