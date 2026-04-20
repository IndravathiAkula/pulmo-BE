package com.ebook.auth.service;

import com.ebook.auth.dto.*;
import com.ebook.auth.entity.*;
import com.ebook.auth.enums.*;
import com.ebook.auth.repository.*;
import com.ebook.common.exception.ConflictException;
import com.ebook.common.service.ConfigService;
import com.ebook.common.service.EmailService;
import com.ebook.user.entity.User;
import com.ebook.user.service.UserProfileService;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.UnauthorizedException;
import com.ebook.common.exception.ValidationException;
import com.ebook.common.util.MetadataUtil;
import com.ebook.common.util.PasswordGenerator;
import com.ebook.common.util.TokenHashUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final DeviceService deviceService;
    private final AuditService auditService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final UserProfileService userProfileService;
    private final EmailService emailService;
    private final ConfigService configService;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordService passwordService,
            JwtService jwtService,
            SessionService sessionService,
            DeviceService deviceService,
            AuditService auditService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            UserProfileService userProfileService,
            EmailService emailService,
            ConfigService configService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.deviceService = deviceService;
        this.auditService = auditService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.userProfileService = userProfileService;
        this.emailService = emailService;
        this.configService = configService;
    }

    // ─────────────────────────── REGISTER ───────────────────────────

    @Transactional
    public UserResponse registerUser(RegisterRequest request, String ipAddress) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Email is already registered");
        }

        UserType userType = parseUserType(request.getUserType());
        if (userType == UserType.AUTHOR) {
            throw new ValidationException("Authors cannot self-register. Please contact admin.");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordService.hashPassword(request.getPassword()));
        user.setEmailVerified(true);
        user.setUserType(userType);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        Role defaultRole = roleRepository.findByName(RoleName.USER)
                .orElseGet(() -> {
                    Role r = new Role(RoleName.USER);
                    roleRepository.save(r);
                    return r;
                });

        UserRole userRole = new UserRole(user, defaultRole);
        userRoleRepository.save(userRole);

        userProfileService.createProfile(user, request.getFirstName(), request.getLastName());

        auditService.logEvent(user.getId(), EventType.REGISTER, ipAddress,
                MetadataUtil.build("email", request.getEmail()));

        LOG.infof("User registered: %s", user.getId());
        return toUserResponse(user, Set.of(RoleName.USER.name()));
    }

    // ─────────────────────────── LOGIN ───────────────────────────

    @Transactional
    public TokenResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new UnauthorizedException("Account is permanently locked. Contact support.");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new UnauthorizedException("Account is temporarily locked. Try again later.");
        }

        if (!passwordService.verifyPassword(user.getPasswordHash(), request.getPassword())) {
            handleFailedLogin(user, ipAddress);
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!user.isEmailVerified()) {
            throw new UnauthorizedException("Email is not verified. Please check your inbox or resend the verification link.");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.update(user);

        Device device = deviceService.registerOrUpdateDevice(user, request.getDeviceFingerprint());
        Set<String> roles = resolveRoles(user.getId());

        String rawRefreshToken = jwtService.generateRefreshToken();
        Session session = sessionService.createSession(user, device, ipAddress, userAgent, rawRefreshToken);

        String accessToken = jwtService.generateAccessToken(
                user.getId(), roles, user.getUserType().name(), session.getId());

        auditService.logEvent(user.getId(), EventType.LOGIN_SUCCESS, ipAddress,
                MetadataUtil.build("deviceId", device.getId().toString()));

        LOG.infof("User logged in: %s from IP: %s", user.getId(), ipAddress);
        return new TokenResponse(accessToken, rawRefreshToken, jwtService.getJwtTtlSeconds());
    }

    // ─────────────────────────── REFRESH ───────────────────────────

    @Transactional
    public TokenResponse refreshToken(RefreshRequest request, String ipAddress, String userAgent) {
        Session session = sessionService.findValidSessionByToken(request.getRefreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        User user = session.getUser();
        if (user == null) {
            throw new UnauthorizedException("User not found");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("Account is not active");
        }

        Device device = deviceService.registerOrUpdateDevice(user, request.getDeviceFingerprint());
        Set<String> roles = resolveRoles(user.getId());

        String newRawRefreshToken = jwtService.generateRefreshToken();

        sessionService.revokeSession(session.getId());
        Session newSession = sessionService.createSession(user, device, ipAddress, userAgent, newRawRefreshToken);

        String newAccessToken = jwtService.generateAccessToken(
                user.getId(), roles, user.getUserType().name(), newSession.getId());

        auditService.logEvent(user.getId(), EventType.TOKEN_REFRESH, ipAddress,
                MetadataUtil.build("newSessionId", newSession.getId().toString()));

        return new TokenResponse(newAccessToken, newRawRefreshToken, jwtService.getJwtTtlSeconds());
    }

    // ─────────────────────────── LOGOUT ───────────────────────────

    @Transactional
    public void logout(String refreshToken, UUID userId, String ipAddress) {
        Optional<Session> sessionOpt = sessionService.findValidSessionByToken(refreshToken);
        sessionOpt.ifPresent(session -> {
            sessionService.revokeSession(session.getId());
            auditService.logEvent(userId, EventType.LOGOUT, ipAddress,
                    MetadataUtil.build("sessionId", session.getId().toString()));
            LOG.infof("User logged out: %s, session: %s", userId, session.getId());
        });
    }

    @Transactional
    public void logoutAll(UUID userId, String ipAddress) {
        sessionService.revokeAllUserSessions(userId);
        auditService.logEvent(userId, EventType.LOGOUT, ipAddress,
                MetadataUtil.build("reason", "global_logout_all_devices"));
        LOG.infof("Global logout for user: %s", userId);
    }

    // ─────────────────────── FORGOT PASSWORD ───────────────────────

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request, String ipAddress) {
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            LOG.debugf("Forgot-password requested for unknown email (suppressed): %s", request.getEmail());
            return;
        }

        User user = userOpt.get();

        String rawToken = TokenHashUtil.generateSecureToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawToken);

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(tokenHash);
        resetToken.setExpiresAt(Instant.now().plus(
                configService.getInt("auth.reset-token-expiry-hours", 1), ChronoUnit.HOURS));
        passwordResetTokenRepository.save(resetToken);

        emailService.sendPasswordReset(user.getEmail(), rawToken);

        auditService.logEvent(user.getId(), EventType.FORGOT_PASSWORD, ipAddress,
                MetadataUtil.build("tokenId", resetToken.getId() != null ? resetToken.getId().toString() : "pending"));
    }

    // ─────────────────────── RESET PASSWORD ───────────────────────

    @Transactional
    public void resetPassword(ResetPasswordRequest request, String ipAddress) {
        String tokenHash = TokenHashUtil.sha256Hex(request.getToken());

        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ValidationException("Invalid or already-used reset token"));

        if (resetToken.isExpired() || resetToken.isUsed()) {
            throw new ValidationException("Reset token has expired or already used. Please request a new one.");
        }

        User user = resetToken.getUser();

        user.setPasswordHash(passwordService.hashPassword(request.getNewPassword()));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.update(user);

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.update(resetToken);

        sessionService.revokeAllUserSessions(user.getId());

        auditService.logEvent(user.getId(), EventType.PASSWORD_RESET, ipAddress,
                MetadataUtil.build("tokenId", resetToken.getId().toString()));

        LOG.infof("Password reset completed for user: %s", user.getId());
    }

    // ─────────────────────── VERIFY EMAIL ───────────────────────

    @Transactional
    public void verifyEmail(VerifyEmailRequest request, String ipAddress) {
        String tokenHash = TokenHashUtil.sha256Hex(request.getToken());

        EmailVerificationToken token = emailVerificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ValidationException("Invalid or already-used verification token"));

        if (token.isExpired() || token.isUsed()) {
            throw new ValidationException("Verification token has expired. Please request a new one.");
        }

        User user = token.getUser();

        if (user.isEmailVerified()) {
            throw new ValidationException("Email is already verified");
        }

        user.setEmailVerified(true);
        userRepository.update(user);

        token.setUsedAt(Instant.now());
        emailVerificationTokenRepository.update(token);

        // For AUTHOR accounts (created by admin): generate a new password and send via email
        if (user.getUserType() == UserType.AUTHOR) {
            String rawPassword = PasswordGenerator.generate();
            user.setPasswordHash(passwordService.hashPassword(rawPassword));
            userRepository.update(user);
            emailService.sendAuthorCredentials(user.getEmail(), rawPassword);
            LOG.infof("Author credentials generated and sent: %s", user.getId());
        }

        auditService.logEvent(user.getId(), EventType.EMAIL_VERIFIED, ipAddress,
                MetadataUtil.build("email", user.getEmail()));

        LOG.infof("Email verified for user: %s", user.getId());
    }

    // ──────────────── RESEND VERIFICATION EMAIL ────────────────

    @Transactional
    public void resendVerificationEmail(String email, String ipAddress) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            LOG.debugf("Resend verification requested for unknown email (suppressed): %s", email);
            return;
        }

        User user = userOpt.get();
        if (user.isEmailVerified()) {
            return;
        }

        sendEmailVerificationToken(user);
        LOG.infof("Verification email resent for user: %s", user.getId());
    }

    // ─────────────────── CHANGE PASSWORD ───────────────────────

    @Transactional
    public void changePassword(ChangePasswordRequest request, UUID userId, String ipAddress) {
        User user = userRepository.findByIdOptional(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordService.verifyPassword(user.getPasswordHash(), request.getCurrentPassword())) {
            throw new UnauthorizedException("Current password is incorrect");
        }

        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new ValidationException("New password must be different from current password");
        }

        user.setPasswordHash(passwordService.hashPassword(request.getNewPassword()));
        userRepository.update(user);

        sessionService.revokeAllUserSessions(userId);

        auditService.logEvent(userId, EventType.PASSWORD_CHANGE, ipAddress,
                MetadataUtil.build("reason", "user_initiated"));

        LOG.infof("Password changed for user: %s", userId);
    }

    // ──────────────────── CURRENT USER ─────────────────────────

    @Transactional
    public UserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findByIdOptional(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Set<String> roles = resolveRoles(userId);
        return toUserResponse(user, roles);
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private void handleFailedLogin(User user, String ipAddress) {
        int maxAttempts = configService.getInt("auth.max-failed-attempts", 5);
        int lockoutMins = configService.getInt("auth.lockout-minutes", 15);

        user.setFailedAttempts(user.getFailedAttempts() + 1);
        if (user.getFailedAttempts() >= maxAttempts) {
            user.setLockedUntil(Instant.now().plusSeconds(lockoutMins * 60L));
            LOG.warnf("Account locked after %d failed attempts: user=%s", maxAttempts, user.getId());
        }
        userRepository.update(user);
        auditService.logEvent(user.getId(), EventType.LOGIN_FAILED, ipAddress,
                MetadataUtil.build("failedAttempts", String.valueOf(user.getFailedAttempts())));
    }

    private Set<String> resolveRoles(UUID userId) {
        List<RoleName> roleNames = userRoleRepository.findRoleNamesByUserId(userId);
        if (roleNames.isEmpty()) {
            return Set.of(RoleName.USER.name());
        }
        return roleNames.stream().map(RoleName::name).collect(Collectors.toSet());
    }

    private UserType parseUserType(String userType) {
        try {
            return UserType.valueOf(userType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid user type: " + userType);
        }
    }

    private UserResponse toUserResponse(User user, Set<String> roles) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getUserType().name(),
                user.getStatus().name(),
                roles,
                user.getCreatedAt());
    }

    private void sendEmailVerificationToken(User user) {
        String rawToken = TokenHashUtil.generateSecureToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawToken);

        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setUser(user);
        verificationToken.setTokenHash(tokenHash);
        verificationToken.setExpiresAt(Instant.now().plus(
                configService.getInt("auth.email-verification-expiry-hours", 24), ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(verificationToken);

        emailService.sendEmailVerification(user.getEmail(), rawToken);
    }

}
