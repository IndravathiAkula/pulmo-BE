package com.ebook.user.service;

import com.ebook.user.entity.User;
import com.ebook.auth.repository.UserRepository;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.user.dto.UpdateProfileRequest;
import com.ebook.user.dto.UserProfileResponse;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class UserProfileService {

    private static final Logger LOG = Logger.getLogger(UserProfileService.class);

    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;

    public UserProfileService(UserProfileRepository userProfileRepository, UserRepository userRepository) {
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public UserProfile createProfile(User user, String firstName, String lastName) {
        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profile.setActive(true);
        userProfileRepository.save(profile);
        return profile;
    }

    @Transactional
    public UserProfileResponse getProfile(UUID userId) {
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found"));
        return toResponse(profile);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found"));

        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhone(request.getPhone());
        profile.setInterests(request.getInterests());
        profile.setDesignation(request.getDesignation());
        profile.setDescription(request.getDescription());
        profile.setQualification(request.getQualification());
        profile.setProfileUrl(request.getProfileUrl());
        userProfileRepository.update(profile);

        LOG.infof("Profile updated for user: %s", userId);
        return toResponse(profile);
    }

    private UserProfileResponse toResponse(UserProfile profile) {
        return UserProfileResponse.builder()
                .id(profile.getId())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .email(profile.getUser().getEmail())
                .phone(profile.getPhone())
                .interests(profile.getInterests())
                .designation(profile.getDesignation())
                .description(profile.getDescription())
                .qualification(profile.getQualification())
                .profileUrl(profile.getProfileUrl())
                .isActive(profile.isActive())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
