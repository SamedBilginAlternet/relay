package com.relay.application.port;

import com.relay.domain.UserSession;
import java.time.Instant;
import java.util.Optional;

public interface SessionRepository {

    UserSession save(UserSession session);

    Optional<UserSession> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    /** Housekeeping — expired rows are useless and only grow the table. */
    void deleteExpired(Instant now);
}
