package com.relay.application.auth;

import com.relay.application.port.Clock;
import com.relay.application.port.PasswordHasher;
import com.relay.application.port.SessionRepository;
import com.relay.application.port.UserRepository;
import com.relay.domain.User;
import com.relay.domain.UserSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registration, sign-in and server-side sessions.
 *
 * <p><b>Scope:</b> Relay is a single shared workspace. Signing in proves who is at the
 * keyboard; it does not partition data. Every signed-in user sees the same connections,
 * runs and playbooks — see docs/ARCHITECTURE.md §4.
 *
 * <p><b>Why database sessions and not a signed cookie:</b> a signed cookie cannot be
 * revoked before it expires, and it would need another secret to rotate. An opaque token
 * hashed into {@code sessions} makes logout real (the row disappears), costs one indexed
 * lookup per request, and keeps the cookie meaningless if it leaks from a log.
 */
public class AuthService {

    public static final Duration SESSION_TTL = Duration.ofDays(30);

    /** Deliberately loose: we check shape, the mail server checks existence. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private static final int MIN_PASSWORD = 8;
    /** BCrypt silently ignores bytes past 72 — refuse instead of pretending. */
    private static final int MAX_PASSWORD = 72;

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, SessionRepository sessions, PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
    }

    // ---- accounts ---------------------------------------------------------

    public User register(String rawEmail, String password, String displayName) {
        String email = normalizeEmail(rawEmail);
        requireEmail(email);
        requirePassword(password);
        if (users.findByEmail(email).isPresent()) {
            throw AuthException.conflict("email", "Bu e-posta ile bir hesap zaten var. Giriş yapmayı dene.");
        }
        Instant now = clock.now();
        User user = new User(UUID.randomUUID(), email, hasher.hash(password),
                displayNameOr(displayName, email), null, User.PROVIDER_PASSWORD, null, now);
        return users.save(user);
    }

    public User login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (email.isBlank() || password == null || password.isBlank()) {
            throw AuthException.unauthorized("E-posta ve parola gerekli.");
        }
        Optional<User> found = users.findByEmail(email);
        // Same sentence for "no such user" and "wrong password" — a login form must not
        // tell a stranger which e-mail addresses have accounts.
        User user = found.orElseThrow(() -> AuthException.unauthorized("E-posta veya parola hatalı."));
        if (user.passwordHash() == null || user.passwordHash().isBlank()) {
            throw new AuthException("password_not_set", "password",
                    "Bu hesap Google ile oluşturulmuş. “Google ile devam et” ile giriş yap.", 401);
        }
        if (!hasher.matches(password, user.passwordHash())) {
            throw AuthException.unauthorized("E-posta veya parola hatalı.");
        }
        return user;
    }

    /**
     * Google sign-in. An existing password account with the same e-mail is reused, not
     * duplicated — the same person signing in a second way.
     */
    public User loginWithGoogle(String rawEmail, String displayName, String avatarUrl) {
        String email = normalizeEmail(rawEmail);
        requireEmail(email);
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            String name = user.displayName() == null || user.displayName().isBlank()
                    ? displayNameOr(displayName, email) : user.displayName();
            String avatar = avatarUrl != null && !avatarUrl.isBlank() ? avatarUrl : user.avatarUrl();
            return users.save(user.withProfile(name, avatar));
        }
        User user = new User(UUID.randomUUID(), email, null, displayNameOr(displayName, email),
                blankToNull(avatarUrl), User.PROVIDER_GOOGLE, null, clock.now());
        return users.save(user);
    }

    public User completeOnboarding(User user) {
        if (user.onboarded()) {
            return user;
        }
        return users.save(user.withOnboardedAt(clock.now()));
    }

    public boolean hasAnyUser() {
        return users.count() > 0;
    }

    // ---- sessions ---------------------------------------------------------

    /** @return the raw cookie value. Only its hash is persisted. */
    public String startSession(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.now();
        sessions.save(new UserSession(UUID.randomUUID(), user.id(), hashToken(token), now, now.plus(SESSION_TTL)));
        return token;
    }

    /** Empty when the token is unknown, expired or malformed. Expired rows are dropped. */
    public Optional<User> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = hashToken(token);
        Optional<UserSession> session = sessions.findByTokenHash(hash);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (session.get().expired(clock.now())) {
            sessions.deleteByTokenHash(hash);
            return Optional.empty();
        }
        return users.findById(session.get().userId());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteByTokenHash(hashToken(token));
        }
    }

    public void purgeExpiredSessions() {
        sessions.deleteExpired(clock.now());
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Lower-casing with {@link Locale#ROOT}: under a Turkish default locale
     * {@code "I".toLowerCase()} is "ı", which would make Ihsan@… and ihsan@… two
     * different accounts.
     */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void requireEmail(String email) {
        if (email.isBlank()) {
            throw AuthException.invalid("email", "E-posta gerekli.");
        }
        if (email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw AuthException.invalid("email", "Geçerli bir e-posta adresi gir.");
        }
    }

    private void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw AuthException.invalid("password", "Parola gerekli.");
        }
        if (password.length() < MIN_PASSWORD) {
            throw AuthException.invalid("password", "Parola en az " + MIN_PASSWORD + " karakter olmalı.");
        }
        if (password.length() > MAX_PASSWORD) {
            throw AuthException.invalid("password", "Parola en fazla " + MAX_PASSWORD + " karakter olabilir.");
        }
    }

    private static String displayNameOr(String displayName, String email) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim().length() > 160 ? displayName.trim().substring(0, 160) : displayName.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
