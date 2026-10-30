package com.guardwork.backend.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.auth.model.PasswordResetToken;
import com.guardwork.backend.auth.model.RefreshToken;
import com.guardwork.backend.auth.repository.PasswordResetTokenRepository;
import com.guardwork.backend.auth.repository.RefreshTokenRepository;
import com.guardwork.backend.auth.service.AccountEmailVerificationService;
import com.guardwork.backend.auth.service.JwtService;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.model.MembershipStatus;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,50}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,128}$");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CompanyMembershipRepository memberships;
    private final CompanyRepository companies;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder, CompanyMembershipRepository memberships, CompanyRepository companies) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.memberships = memberships;
        this.companies = companies;
    }

    @Transactional
    public UserDto register(RegisterUserRequest request) {
        log.debug("event=auth_registration outcome=started");
        validateRegistration(request);

        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 USERNAME_OR_EMAIL_EXISTS: username already taken");
        }
        if (userRepository.findByEmail(request.email().trim().toLowerCase(Locale.ROOT)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 USERNAME_OR_EMAIL_EXISTS: email already registered");
        }

        User user = new User();
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setUsername(request.username());
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setEmailVerified(false);

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "40901 USERNAME_OR_EMAIL_EXISTS: Username or email already registered");
        }
        log.debug("event=auth_registration outcome=saved");
        return UserDto.from(saved);
    }

    @Transactional
    public TokenPairResponse login(LoginRequest request, String ipAddress, String userAgent) {
        return login(request, ipAddress, userAgent, null);
    }

    @Transactional
    public TokenPairResponse login(LoginRequest request, String ipAddress, String userAgent, Portal portal) {
        log.debug("event=auth_login outcome=started");
        if (request == null || blank(request.usernameOrEmail()) || blank(request.password())) {
            throw badRequest("40001 INVALID_INPUT: usernameOrEmail and password are required");
        }

        String identifier = request.usernameOrEmail().trim();
        Optional<User> byUsername = userRepository.findByUsername(identifier);
        Optional<User> userOpt = byUsername.isPresent() ? byUsername
                : userRepository.findByEmail(identifier.toLowerCase());

        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPassword())) {
            log.debug("event=auth_login outcome=invalid_credentials");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40101 INVALID_CREDENTIALS: invalid credentials");
        }

        User user = userOpt.get();
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
        }

        portal = portal == null ? Portal.fromRole(user.getRole()) : portal;
        portal.requireRole(user.getRole());
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), portal);
        String rawRefreshToken = jwtService.generateRefreshTokenValue();
        String tokenHash = jwtService.hashToken(rawRefreshToken);
        String familyId = jwtService.generateFamilyId();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setPortal(portal.value());
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setFamilyId(familyId);
        refreshToken.setRevoked(false);
        refreshToken.setExpiresAt(Instant.now().plusMillis(jwtService.getRefreshExpirationMs()));
        refreshToken.setIpAddress(ipAddress != null ? ipAddress : "127.0.0.1");
        refreshToken.setUserAgent(userAgent);
        refreshTokenRepository.save(refreshToken);

        log.debug("event=auth_login outcome=token_pair_issued");
        return TokenPairResponse.of(accessToken, rawRefreshToken, jwtService.getAccessExpirationMs() / 1000, UserDto.from(user));
    }

    @Transactional
    public TokenPairResponse refreshToken(RefreshTokenRequest request, String ipAddress, String userAgent) {
        return refreshToken(request, ipAddress, userAgent, null);
    }

    @Transactional
    public TokenPairResponse refreshToken(RefreshTokenRequest request, String ipAddress, String userAgent, Portal portal) {
        log.debug("event=auth_refresh outcome=started");
        if (request == null || blank(request.refreshToken())) {
            throw badRequest("40001 INVALID_INPUT: refreshToken is required");
        }

        String rawToken = request.refreshToken().trim();
        String tokenHash = jwtService.hashToken(rawToken);

        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);
        if (tokenOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40104 REVOKED_REFRESH_TOKEN: Invalid refresh token");
        }

        RefreshToken token = tokenOpt.get();
        portal = portal == null ? Portal.parse(token.getPortal()) : portal;
        requireRefreshPortal(token, portal);

        // Reuse attack detection: if token is already revoked, revoke its entire family!
        if (token.isRevoked()) {
            log.debug("event=auth_refresh outcome=reuse_detected");
            refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40104 REVOKED_REFRESH_TOKEN: Token reuse detected. Session family revoked.");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40104 REVOKED_REFRESH_TOKEN: Refresh token has expired");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40401 USER_NOT_FOUND: User not found"));

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
        }

        portal.requireRole(user.getRole());
        // Validate portal and current authority before consuming the refresh token.
        refreshTokenRepository.revokeToken(token.getId(), Instant.now());
        // Issue new access token and rotated refresh token with the SAME familyId and portal.
        String newAccessToken = jwtService.generateAccessToken(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), portal);
        String newRawRefreshToken = jwtService.generateRefreshTokenValue();
        String newTokenHash = jwtService.hashToken(newRawRefreshToken);

        RefreshToken newToken = new RefreshToken();
        newToken.setUserId(user.getId());
        newToken.setPortal(portal.value());
        newToken.setTokenHash(newTokenHash);
        newToken.setFamilyId(token.getFamilyId());
        newToken.setRevoked(false);
        newToken.setExpiresAt(Instant.now().plusMillis(jwtService.getRefreshExpirationMs()));
        newToken.setIpAddress(ipAddress != null ? ipAddress : "127.0.0.1");
        newToken.setUserAgent(userAgent);
        refreshTokenRepository.save(newToken);

        log.debug("event=auth_refresh outcome=token_pair_rotated");
        return TokenPairResponse.of(newAccessToken, newRawRefreshToken, jwtService.getAccessExpirationMs() / 1000, UserDto.from(user));
    }

    public void logout(String rawRefreshToken) {
        logout(rawRefreshToken, null);
    }

    public void logout(String rawRefreshToken, Portal portal) {
        log.debug("event=auth_logout outcome=started");
        if (!blank(rawRefreshToken)) {
            String tokenHash = jwtService.hashToken(rawRefreshToken.trim());
            refreshTokenRepository.findByTokenHash(tokenHash)
                    .ifPresent(token -> {
                        if (portal != null) requireRefreshPortal(token, portal);
                        refreshTokenRepository.revokeToken(token.getId(), Instant.now());
                    });
        }
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "40401 USER_NOT_FOUND: User not found"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "40304 ACCOUNT_SUSPENDED: Account is suspended");
        }
        return new CurrentUserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isEmailVerified(),
                AccountEmailVerificationService.isRecruitingEmailEligible(user),
                memberships.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE)
                        .map(m -> companies.findById(m.getCompanyId())
                                .map(c -> new CurrentUserResponse.ActiveMembershipDto(
                                    c.getId(), c.getName(), m.getRole().name(), m.getJoinedAt()))
                                .orElse(null)).orElse(null)
        );
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        log.debug("event=auth_password_change outcome=started");
        if (request == null || blank(request.currentPassword()) || blank(request.newPassword())) {
            throw badRequest("40001 INVALID_INPUT: currentPassword and newPassword are required");
        }
        if (!PASSWORD_PATTERN.matcher(request.newPassword()).matches()) {
            throw badRequest("40002 WEAK_PASSWORD: password must be 8-128 characters with at least one uppercase letter, one lowercase letter, and one digit");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "40401 USER_NOT_FOUND: User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw badRequest("40001 INVALID_INPUT: Current password does not match");
        }

        userRepository.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
        // Invalidate all refresh tokens on password change
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.debug("event=auth_password_change outcome=password_updated_sessions_revoked");
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        // Keep the same event regardless of whether an account exists.
        log.debug("event=auth_password_reset_request outcome=received");
        if (request == null || blank(request.email()) || !EMAIL_PATTERN.matcher(request.email()).matches()) {
            return; // Return silently to avoid email enumeration
        }

        Optional<User> userOpt = userRepository.findByEmail(request.email().toLowerCase().trim());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String rawToken = jwtService.generateRefreshTokenValue();
            String tokenHash = jwtService.hashToken(rawToken);

            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUserId(user.getId());
            resetToken.setTokenHash(tokenHash);
            resetToken.setConsumed(false);
            resetToken.setExpiresAt(Instant.now().plusSeconds(900)); // 15 mins
            passwordResetTokenRepository.save(resetToken);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        log.debug("event=auth_password_reset outcome=started");
        if (request == null || blank(request.token()) || blank(request.newPassword())) {
            throw badRequest("40001 INVALID_INPUT: token and newPassword are required");
        }
        if (!PASSWORD_PATTERN.matcher(request.newPassword()).matches()) {
            throw badRequest("40002 WEAK_PASSWORD: password must be 8-128 characters with at least one uppercase letter, one lowercase letter, and one digit");
        }

        String tokenHash = jwtService.hashToken(request.token().trim());
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> badRequest("40001 INVALID_INPUT: Invalid or expired password reset token"));

        if (resetToken.isConsumed() || resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw badRequest("40001 INVALID_INPUT: Password reset token has expired or already been consumed");
        }

        passwordResetTokenRepository.markConsumed(resetToken.getId());
        userRepository.updatePassword(resetToken.getUserId(), passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllForUser(resetToken.getUserId(), Instant.now());
        log.debug("event=auth_password_reset outcome=password_updated_sessions_revoked");
    }

    private void validateRegistration(RegisterUserRequest request) {
        if (request == null) {
            throw badRequest("40001 INVALID_INPUT: Request body is required");
        }
        if (blank(request.firstName()) || request.firstName().trim().length() > 100) {
            throw badRequest("40001 INVALID_INPUT: First name must be 1-100 characters");
        }
        if (blank(request.lastName()) || request.lastName().trim().length() > 100) {
            throw badRequest("40001 INVALID_INPUT: Last name must be 1-100 characters");
        }
        if (blank(request.username()) || !USERNAME_PATTERN.matcher(request.username()).matches()) {
            throw badRequest("40001 INVALID_INPUT: Username must be 3-50 characters (letters, digits, underscore)");
        }
        if (blank(request.email()) || request.email().trim().length() > 255 || !EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw badRequest("40001 INVALID_INPUT: A valid email up to 255 characters is required");
        }
        if (blank(request.password()) || !PASSWORD_PATTERN.matcher(request.password()).matches()) {
            throw badRequest("40002 WEAK_PASSWORD: password must be 8-128 characters with at least one uppercase letter, one lowercase letter, and one digit");
        }
    }

    private static void requireRefreshPortal(RefreshToken token, Portal portal) {
        if (!portal.value().equals(token.getPortal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "40100 UNAUTHORIZED: Refresh token portal mismatch");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
