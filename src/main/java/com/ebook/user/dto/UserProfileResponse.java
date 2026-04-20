package com.ebook.user.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String interests;
    private String designation;
    private String description;
    private String qualification;
    private String profileUrl;
    private boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
