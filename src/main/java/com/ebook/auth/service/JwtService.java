package com.ebook.auth.service;

import com.ebook.common.service.ConfigService;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JwtService {

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConfigService configService;

    // jwt-issuer stays in properties because mp.jwt.verify.issuer must match
    @ConfigProperty(name = "mp.jwt.verify.issuer", defaultValue = "ebookhub-auth")
    String jwtIssuer;

    public JwtService(ConfigService configService) {
        this.configService = configService;
    }

    public String generateAccessToken(UUID userId, Set<String> roles, String userType, UUID sessionId) {
        return Jwt.issuer(jwtIssuer)
                .subject(userId.toString())
                .upn(userId.toString())
                .groups(roles)
                .claim("userType", userType)
                .claim("sessionId", sessionId.toString())
                .expiresIn(getJwtTtlSeconds())
                .sign();
    }

    public long getJwtTtlSeconds() {
        return configService.getLong("auth.jwt-ttl-seconds", 900);
    }

    public String generateRefreshToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
