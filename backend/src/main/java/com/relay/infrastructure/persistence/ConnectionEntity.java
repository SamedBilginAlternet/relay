package com.relay.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code config} is AES-GCM ciphertext. Nothing here is ever logged. */
@Entity
@Table(name = "connections")
public class ConnectionEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "ConnectionEntity[provider=" + provider + ", config=<encrypted>]";
    }
}
