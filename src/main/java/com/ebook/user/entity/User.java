package com.ebook.user.entity;

import com.ebook.auth.entity.Device;
import com.ebook.auth.entity.Session;
import com.ebook.auth.entity.UserRole;
import com.ebook.auth.enums.UserStatus;
import com.ebook.auth.enums.UserType;
import com.ebook.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_users")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 50)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UserStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    // ── Relationships (owning side is the child) ──
    // CascadeType.REMOVE + orphanRemoval ensures that deleting a User wipes its role
    // assignments, active sessions, and registered devices. Without this the children
    // become orphans (or the delete outright fails on FK constraints), leaving a
    // broken "delete user" flow. Other child entities (CartItem, UserBook, tokens,
    // audit logs) are not mapped on this side — a full deleteUser service must still
    // clean those up explicitly before invoking the JPA delete.

    @JsonIgnore
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY,
            cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<UserRole> userRoles = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY,
            cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<Session> sessions = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY,
            cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<Device> devices = new ArrayList<>();

}
