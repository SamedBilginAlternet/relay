package com.relay.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone who can sign in. Relay runs as a single shared workspace: a user is an
 * identity, not a tenant — runs, connections and policies are global.
 *
 * <p>{@code passwordHash} is null for accounts created through Google sign-in.
 */
public record User(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        String avatarUrl,
        String provider,
        Instant onboardedAt,
        Instant createdAt) {

    public static final String PROVIDER_PASSWORD = "password";
    public static final String PROVIDER_GOOGLE = "google";

    public boolean onboarded() {
        return onboardedAt != null;
    }

    public User withPasswordHash(String hash) {
        return new User(id, email, hash, displayName, avatarUrl, provider, onboardedAt, createdAt);
    }

    public User withProfile(String name, String avatar) {
        return new User(id, email, passwordHash, name, avatar, provider, onboardedAt, createdAt);
    }

    public User withOnboardedAt(Instant at) {
        return new User(id, email, passwordHash, displayName, avatarUrl, provider, at, createdAt);
    }

    /** Never let a hash reach a log line. */
    @Override
    public String toString() {
        return "User[email=" + email + ", provider=" + provider + "]";
    }
}
