package com.relay.application.port;

/**
 * One-way password hashing. The application layer only needs these two verbs;
 * BCrypt lives in {@code infrastructure.auth}.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    /** False (never an exception) when the stored hash is null or malformed. */
    boolean matches(String rawPassword, String storedHash);
}
