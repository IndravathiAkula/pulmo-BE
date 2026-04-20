package com.ebook.admin.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorResponse {
    private UUID id;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private String designation;
    private String description;
    private String qualification;
    private String profileUrl;
    private boolean emailVerified;
    private boolean isActive;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
