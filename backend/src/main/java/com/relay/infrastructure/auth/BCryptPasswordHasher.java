package com.relay.infrastructure.auth;

import com.relay.application.port.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * BCrypt from {@code spring-security-crypto} — the one class we need, without pulling
 * in the Spring Security filter chain (which would take over the existing endpoints).
 *
 * <p>Cost 10 is ~50ms per hash on this hardware: slow enough to make an offline attack
 * expensive, fast enough that a login is not a visible wait.
 */
public class BCryptPasswordHasher implements PasswordHasher {

    private final int cost;

    public BCryptPasswordHasher() {
        this(10);
    }

    public BCryptPasswordHasher(int cost) {
        this.cost = cost;
    }

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("password is required");
        }
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost));
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || rawPassword.isEmpty() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // Not a BCrypt hash (hand-edited row, old format) — treat as "does not match".
            return false;
        }
    }
}
