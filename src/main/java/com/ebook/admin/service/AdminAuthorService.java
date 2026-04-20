package com.ebook.admin.service;

import com.ebook.admin.dto.AuthorResponse;
import com.ebook.admin.dto.CreateAuthorRequest;
import com.ebook.admin.dto.UpdateAuthorRequest;
import com.ebook.auth.entity.EmailVerificationToken;
import com.ebook.auth.entity.Role;
import com.ebook.auth.entity.UserRole;
import com.ebook.auth.enums.EventType;
import com.ebook.auth.enums.RoleName;
import com.ebook.auth.enums.UserStatus;
import com.ebook.auth.enums.UserType;
import com.ebook.auth.repository.EmailVerificationTokenRepository;
import com.ebook.auth.repository.RoleRepository;
import com.ebook.auth.repository.UserRepository;
import com.ebook.auth.repository.UserRoleRepository;
import com.ebook.auth.service.AuditService;
import com.ebook.auth.service.PasswordService;
import com.ebook.common.dto.PagedResponse;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.exception.ConflictException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.ValidationException;
import com.ebook.common.service.ConfigService;
import com.ebook.common.service.EmailService;
import com.ebook.common.util.MetadataUtil;
import com.ebook.common.util.PasswordGenerator;
import com.ebook.common.util.TokenHashUtil;
import com.ebook.user.entity.User;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AdminAuthorService {

    private static final Logger LOG = Logger.getLogger(AdminAuthorService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AuditService auditService;
    private final ConfigService configService;

    public AdminAuthorService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            UserProfileRepository userProfileRepository,
            PasswordService passwordService,
            EmailService emailService,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            AuditService auditService,
            ConfigService configService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.auditService = auditService;
        this.configService = configService;
    }

    // ─────────────────────────── CREATE ───────────────────────────

    @Transactional
    public AuthorResponse createAuthor(CreateAuthorRequest request, String ipAddress) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordService.hashPassword(PasswordGenerator.generate()));
        user.setEmailVerified(false);
        user.setUserType(UserType.AUTHOR);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseThrow(() -> new ResourceNotFoundException("USER role not found. Run seeder first."));
        userRoleRepository.save(new UserRole(user, userRole));

        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhone(request.getPhone());
        profile.setDesignation(request.getDesignation());
        profile.setDescription(request.getDescription());
        profile.setQualification(request.getQualification());
        profile.setProfileUrl(request.getProfileUrl());
        profile.setActive(true);
        userProfileRepository.save(profile);

        sendVerificationEmail(user);

        auditService.logEvent(user.getId(), EventType.REGISTER, ipAddress,
                MetadataUtil.build("createdBy", "admin", "userType", "AUTHOR"));

        LOG.infof("Author created by admin: %s (%s)", user.getId(), request.getEmail());
        return toResponse(user, profile);
    }

    // ─────────────────────────── UPDATE ───────────────────────────

    @Transactional
    public AuthorResponse updateAuthor(UUID authorId, UpdateAuthorRequest request, String ipAddress) {
        User user = findAuthorOrThrow(authorId);
        UserProfile profile = findProfileOrThrow(authorId);

        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhone(request.getPhone());
        profile.setDesignation(request.getDesignation());
        profile.setDescription(request.getDescription());
        profile.setQualification(request.getQualification());
        profile.setProfileUrl(request.getProfileUrl());
        userProfileRepository.update(profile);

        auditService.logEvent(authorId, EventType.AUTHOR_UPDATED, ipAddress,
                MetadataUtil.build("entity", "AUTHOR", "entityId", authorId.toString(),
                        "email", user.getEmail()));

        LOG.infof("Author updated by admin: %s", authorId);
        return toResponse(user, profile);
    }

    // ─────────────────────────── DELETE (soft — visibility only) ───────────────────────────

    @Transactional
    public void deactivateAuthor(UUID authorId, String ipAddress) {
        User user = findAuthorOrThrow(authorId);
        UserProfile profile = findProfileOrThrow(authorId);

        profile.setActive(false);
        userProfileRepository.update(profile);

        auditService.logEvent(authorId, EventType.AUTHOR_DEACTIVATED, ipAddress,
                MetadataUtil.build("action", "author_deactivated"));

        LOG.infof("Author deactivated by admin: %s", authorId);
    }

    // ─────────────────────────── TOGGLE ───────────────────────────

    @Transactional
    public AuthorResponse toggleActive(UUID authorId, String ipAddress) {
        User user = findAuthorOrThrow(authorId);
        UserProfile profile = findProfileOrThrow(authorId);

        profile.setActive(!profile.isActive());
        userProfileRepository.update(profile);

        EventType event = profile.isActive() ? EventType.AUTHOR_ACTIVATED : EventType.AUTHOR_DEACTIVATED;
        auditService.logEvent(authorId, event, ipAddress,
                MetadataUtil.build("isActive", String.valueOf(profile.isActive())));

        LOG.infof("Author %s by admin: %s",
                profile.isActive() ? "activated" : "deactivated", authorId);
        return toResponse(user, profile);
    }

    // ─────────────────────────── LIST (Admin — all authors) ───────────────────────────

    @Transactional
    public List<AuthorResponse> getAllAuthors() {
        return userProfileRepository.findAllByUserType(UserType.AUTHOR).stream()
                .map(profile -> toResponse(profile.getUser(), profile))
                .toList();
    }

    // ─────────────────────────── LIST (Public — active + verified only) ───────────────────────────

    @Transactional
    public List<AuthorResponse> getActiveAuthors() {
        return userProfileRepository.findActiveAuthorProfiles().stream()
                .map(profile -> toResponse(profile.getUser(), profile))
                .toList();
    }

    // ─────────────────────────── PAGED VARIANTS ───────────────────────────

    @Transactional
    public PagedResponse<AuthorResponse> getAllAuthors(PageRequest req) {
        return PagedResponse.of(
                userProfileRepository.findAllByUserTypePage(UserType.AUTHOR, req),
                userProfileRepository.countByUserType(UserType.AUTHOR),
                req,
                profile -> toResponse(profile.getUser(), profile));
    }

    @Transactional
    public PagedResponse<AuthorResponse> getActiveAuthors(PageRequest req) {
        return PagedResponse.of(
                userProfileRepository.findActiveAuthorProfilesPage(req),
                userProfileRepository.countActiveAuthors(),
                req,
                profile -> toResponse(profile.getUser(), profile));
    }

    // ─────────────────────────── GET BY ID ───────────────────────────

    @Transactional
    public AuthorResponse getAuthorById(UUID authorId) {
        User user = findAuthorOrThrow(authorId);
        UserProfile profile = findProfileOrThrow(authorId);
        return toResponse(user, profile);
    }

    // ─────────────────────────── RESEND VERIFICATION ───────────────────────────

    @Transactional
    public void resendVerification(UUID authorId, String ipAddress) {
        User user = findAuthorOrThrow(authorId);

        if (user.isEmailVerified()) {
            throw new ValidationException("Author email is already verified");
        }

        sendVerificationEmail(user);
        LOG.infof("Verification email resent for author: %s", authorId);
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private User findAuthorOrThrow(UUID authorId) {
        User user = userRepository.findByIdOptional(authorId)
                .orElseThrow(() -> new ResourceNotFoundException("Author not found"));
        if (user.getUserType() != UserType.AUTHOR) {
            throw new ResourceNotFoundException("Author not found");
        }
        return user;
    }

    private UserProfile findProfileOrThrow(UUID userId) {
        return userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Author profile not found"));
    }

    private void sendVerificationEmail(User user) {
        String rawToken = TokenHashUtil.generateSecureToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawToken);

        int expiryHours = configService.getInt("auth.email-verification-expiry-hours", 24);

        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setUser(user);
        verificationToken.setTokenHash(tokenHash);
        verificationToken.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(verificationToken);

        emailService.sendEmailVerification(user.getEmail(), rawToken);
    }

    private AuthorResponse toResponse(User user, UserProfile profile) {
        return AuthorResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .phone(profile.getPhone())
                .designation(profile.getDesignation())
                .description(profile.getDescription())
                .qualification(profile.getQualification())
                .profileUrl(profile.getProfileUrl())
                .emailVerified(user.isEmailVerified())
                .isActive(profile.isActive())
                .status(user.getStatus().name())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
