package com.relay.support;

import com.relay.application.port.SessionRepository;
import com.relay.application.port.UserRepository;
import com.relay.domain.User;
import com.relay.domain.UserSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory user and session ports — sign-in tested without JPA or a database. */
public final class AuthDoubles {

    private AuthDoubles() {
    }

    public static final class InMemoryUsers implements UserRepository {
        public final List<User> rows = new ArrayList<>();

        @Override
        public Optional<User> findByEmail(String email) {
            return rows.stream().filter(u -> u.email().equals(email)).findFirst();
        }

        @Override
        public Optional<User> findById(UUID id) {
            return rows.stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public User save(User user) {
            rows.removeIf(u -> u.id().equals(user.id()));
            rows.add(user);
            return user;
        }

        @Override
        public long count() {
            return rows.size();
        }
    }

    public static final class InMemorySessions implements SessionRepository {
        public final List<UserSession> rows = new ArrayList<>();
        private final Map<String, UserSession> byHash = new LinkedHashMap<>();

        @Override
        public UserSession save(UserSession session) {
            rows.add(session);
            byHash.put(session.tokenHash(), session);
            return session;
        }

        @Override
        public Optional<UserSession> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public void deleteByTokenHash(String tokenHash) {
            rows.removeIf(s -> s.tokenHash().equals(tokenHash));
            byHash.remove(tokenHash);
        }

        @Override
        public void deleteExpired(Instant now) {
            rows.removeIf(s -> s.expired(now));
            byHash.values().removeIf(s -> s.expired(now));
        }
    }
}
