package com.ebook.auth.dto;

import lombok.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private UUID id;
    private String email;
    private String userType;
    private String status;
    private Set<String> roles;
    private Instant createdAt;
}
