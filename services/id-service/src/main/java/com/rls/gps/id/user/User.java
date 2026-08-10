package com.rls.gps.id.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user of one company. Every location ping and metadata document is ultimately keyed by
 * {@code (companyId, userId)}.
 *
 * <p>The company is held as an id rather than a JPA association: the two are separate aggregates,
 * and the id is exactly what the rest of the platform passes around in headers.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "company_id", length = 36, nullable = false, updatable = false)
    private String companyId;

    @Column(name = "username", length = 150, nullable = false)
    private String username;

    @Column(name = "display_name", length = 150)
    private String displayName;

    /** BCrypt hash - the plaintext password is never stored. */
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // for JPA
    }

    private User(String id, String companyId, String username, String displayName, String passwordHash,
                 UserStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User register(String companyId, String username, String displayName, String passwordHash,
                                Instant now) {
        return new User(UUID.randomUUID().toString(), companyId, username, displayName, passwordHash,
                UserStatus.ACTIVE, now, now);
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
