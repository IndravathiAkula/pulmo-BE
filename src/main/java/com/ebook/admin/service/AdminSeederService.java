package com.ebook.admin.service;

import com.ebook.auth.entity.Role;
import com.ebook.auth.entity.UserRole;
import com.ebook.auth.enums.RoleName;
import com.ebook.auth.enums.UserStatus;
import com.ebook.auth.enums.UserType;
import com.ebook.auth.repository.RoleRepository;
import com.ebook.auth.repository.UserRepository;
import com.ebook.auth.repository.UserRoleRepository;
import com.ebook.auth.service.PasswordService;
import com.ebook.catalog.entity.Category;
import com.ebook.catalog.repository.CategoryRepository;
import com.ebook.common.entity.ConfigParam;
import com.ebook.common.repository.ConfigParamRepository;
import com.ebook.common.service.ConfigService;
import com.ebook.user.entity.User;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Handles one-time seeding of master data.
 * Triggered via POST /admin/seed (ADMIN only).
 * Idempotent — checks system.seeder-executed flag before running.
 */
@ApplicationScoped
public class AdminSeederService {

    private static final Logger LOG = Logger.getLogger(AdminSeederService.class);
    private static final String SEEDER_FLAG_KEY = "system.seeder-executed";
    private static final String DEFAULT_ADMIN_EMAIL = "taskt600@gmail.com";
    private static final String DEFAULT_ADMIN_PASSWORD = "Admin@12345";

    private final RoleRepository roleRepository;
    private final CategoryRepository categoryRepository;
    private final ConfigParamRepository configParamRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordService passwordService;
    private final ConfigService configService;

    public AdminSeederService(
            RoleRepository roleRepository,
            CategoryRepository categoryRepository,
            ConfigParamRepository configParamRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            UserProfileRepository userProfileRepository,
            PasswordService passwordService,
            ConfigService configService) {
        this.roleRepository = roleRepository;
        this.categoryRepository = categoryRepository;
        this.configParamRepository = configParamRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordService = passwordService;
        this.configService = configService;
    }

    /**
     * Executes the full seed process. Safe to call multiple times — exits early if already seeded.
     * The entire operation is wrapped in a single transaction — rolls back completely on failure.
     */
    @Transactional
    public SeederResult seed() {
        if (isAlreadySeeded()) {
            LOG.info("Seeder: Already executed — skipping");
            return SeederResult.ALREADY_SEEDED;
        }

        LOG.info("Seeder: Starting master data seeding...");

        int rolesSeeded = seedRoles();
        int categoriesSeeded = seedCategories();
        int configSeeded = seedConfigParams();
        boolean adminCreated = seedAdminUser();

        markAsSeeded();
        configService.refreshCache();

        LOG.infof("Seeder: Completed — roles=%d, categories=%d, config=%d, adminCreated=%s",
                rolesSeeded, categoriesSeeded, configSeeded, adminCreated);

        return SeederResult.SUCCESS;
    }

    // ─────────────────────────── SEED ROLES ───────────────────────────

    private int seedRoles() {
        int count = 0;
        for (RoleName roleName : RoleName.values()) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(new Role(roleName));
                count++;
                LOG.infof("Seeder: Created role — %s", roleName);
            }
        }
        return count;
    }

    // ─────────────────────────── SEED CATEGORIES ───────────────────────────

    private int seedCategories() {
        List<String[]> categories = List.<String[]>of(
                new String[]{"Pulmonology", "pulmonology", "Lungs and respiratory system"}
        );

        int count = 0;
        for (String[] cat : categories) {
            if (categoryRepository.findBySlug(cat[1]).isEmpty()) {
                Category category = new Category();
                category.setName(cat[0]);
                category.setSlug(cat[1]);
                category.setDescription(cat[2]);
                category.setActive(true);
                categoryRepository.save(category);
                count++;
            }
        }
        LOG.infof("Seeder: Seeded %d categories", count);
        return count;
    }

    // ─────────────────────────── SEED CONFIG PARAMS ───────────────────────────

    private int seedConfigParams() {
        List<ConfigParam> defaults = List.of(
                new ConfigParam("Max Failed Login Attempts", "auth.max-failed-attempts", "5", "5", "INTEGER"),
                new ConfigParam("Account Lockout Duration (minutes)", "auth.lockout-minutes", "15", "15", "INTEGER"),
                new ConfigParam("Password Reset Token Expiry (hours)", "auth.reset-token-expiry-hours", "1", "1", "INTEGER"),
                new ConfigParam("Email Verification Token Expiry (hours)", "auth.email-verification-expiry-hours", "24", "24", "INTEGER"),
                new ConfigParam("JWT Access Token TTL (seconds)", "auth.jwt-ttl-seconds", "900", "900", "LONG"),
                new ConfigParam("Session Expiry (days)", "auth.session-expiry-days", "30", "30", "INTEGER"),
                new ConfigParam("Frontend Base URL", "app.frontend-url", "http://localhost:3000", "http://localhost:3000", "STRING"),
                new ConfigParam("Admin Email for Notifications", "app.admin-email", "taskt600@gmail.com", "taskt600@gmail.com", "STRING")
        );

        int count = 0;
        for (ConfigParam param : defaults) {
            if (configParamRepository.findByKey(param.getKey()).isEmpty()) {
                configParamRepository.save(param);
                count++;
            }
        }
        LOG.infof("Seeder: Seeded %d config params", count);
        return count;
    }

    // ─────────────────────────── SEED ADMIN USER ───────────────────────────

    private boolean seedAdminUser() {
        if (userRepository.findByEmail(DEFAULT_ADMIN_EMAIL).isPresent()) {
            LOG.info("Seeder: Admin user already exists — skipping");
            return false;
        }

        // Create admin user
        User admin = new User();
        admin.setEmail(DEFAULT_ADMIN_EMAIL);
        admin.setPasswordHash(passwordService.hashPassword(DEFAULT_ADMIN_PASSWORD));
        admin.setEmailVerified(true);
        admin.setUserType(UserType.READER);
        admin.setStatus(UserStatus.ACTIVE);
        userRepository.save(admin);

        // Assign ADMIN role
        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role not found — seed roles first"));
        userRoleRepository.save(new UserRole(admin, adminRole));

        // Also assign USER role (admin has both)
        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException("USER role not found — seed roles first"));
        userRoleRepository.save(new UserRole(admin, userRole));

        // Create admin profile
        UserProfile profile = new UserProfile();
        profile.setUser(admin);
        profile.setFirstName("System");
        profile.setLastName("Admin");
        profile.setActive(true);
        userProfileRepository.save(profile);

        LOG.infof("Seeder: Created admin user — %s (password: %s — CHANGE IMMEDIATELY)",
                DEFAULT_ADMIN_EMAIL, DEFAULT_ADMIN_PASSWORD);
        return true;
    }

    // ─────────────────────────── RUN-ONCE CHECK ───────────────────────────

    private boolean isAlreadySeeded() {
        return configParamRepository.findByKey(SEEDER_FLAG_KEY).isPresent();
    }

    private void markAsSeeded() {
        ConfigParam flag = new ConfigParam(
                "Seeder Execution Flag", SEEDER_FLAG_KEY, "true", "false", "BOOLEAN");
        configParamRepository.persist(flag);
    }

    // ─────────────────────────── RESULT ───────────────────────────

    public enum SeederResult {
        SUCCESS,
        ALREADY_SEEDED
    }
}
