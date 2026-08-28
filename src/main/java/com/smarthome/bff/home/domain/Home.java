package com.smarthome.bff.home.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "homes", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "name"}))
public class Home {

    @Id
    @UuidGenerator
    private UUID id;

    /** Username (JWT {@code sub}); there is no separate user table. */
    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean isDefault;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Home() {
    }

    public Home(String userId, String name, boolean isDefault) {
        this.userId = userId;
        this.name = name;
        this.isDefault = isDefault;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
