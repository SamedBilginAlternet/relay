package com.relay.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A signed-in browser. {@code tokenHash} is the SHA-256 of the cookie value —
 * the cookie itself is never stored, so the table cannot be replayed as a login.
 */
public record UserSession(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @Override
    public String toString() {
        return "UserSession[user=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
