package edu.hitsz.canteen.model;

import java.time.LocalDateTime;
import java.util.Objects;

public final class User {
    private final String userId;
    private final String username;
    private final String passwordSalt;
    private final String passwordHash;
    private final Role role;
    private final UserStatus userStatus;
    private final LocalDateTime createdAt;

    public User(
            String userId,
            String username,
            String passwordSalt,
            String passwordHash,
            Role role,
            UserStatus userStatus,
            LocalDateTime createdAt) {
        this.userId = Objects.requireNonNull(userId);
        this.username = Objects.requireNonNull(username);
        this.passwordSalt = Objects.requireNonNull(passwordSalt);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.userStatus = Objects.requireNonNull(userStatus);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getUserStatus() {
        return userStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
