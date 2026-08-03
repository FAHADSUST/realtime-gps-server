package com.rls.gps.id.company;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tenant of the platform. Every user, location and metadata row is scoped to one company.
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    /** Public identifier used by clients; unique across the platform. */
    @Column(name = "app_key", length = 64, nullable = false, unique = true, updatable = false)
    private String appKey;

    /** BCrypt hash - the plaintext secret is returned once at signup and never stored. */
    @Column(name = "app_secret_hash", length = 100, nullable = false)
    private String appSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private CompanyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Company() {
        // for JPA
    }

    private Company(String id, String name, String contactEmail, String appKey, String appSecretHash,
                    CompanyStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.contactEmail = contactEmail;
        this.appKey = appKey;
        this.appSecretHash = appSecretHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Company register(String name, String contactEmail, String appKey, String appSecretHash,
                                   Instant now) {
        return new Company(UUID.randomUUID().toString(), name, contactEmail, appKey, appSecretHash,
                CompanyStatus.ACTIVE, now, now);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecretHash() {
        return appSecretHash;
    }

    public CompanyStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == CompanyStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
