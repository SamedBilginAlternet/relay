package com.relay.infrastructure.persistence;

import com.relay.application.port.SessionRepository;
import com.relay.domain.UserSession;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaSessionRepository implements SessionRepository {

    private final SessionEntityRepository sessions;

    public JpaSessionRepository(SessionEntityRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public UserSession save(UserSession session) {
        SessionEntity entity = new SessionEntity();
        entity.setId(session.id());
        entity.setUserId(session.userId());
        entity.setTokenHash(session.tokenHash());
        entity.setCreatedAt(session.createdAt());
        entity.setExpiresAt(session.expiresAt());
        sessions.save(entity);
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSession> findByTokenHash(String tokenHash) {
        return sessions.findByTokenHash(tokenHash)
                .map(e -> new UserSession(e.getId(), e.getUserId(), e.getTokenHash(), e.getCreatedAt(),
                        e.getExpiresAt()));
    }

    @Override
    @Transactional
    public void deleteByTokenHash(String tokenHash) {
        sessions.deleteByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public void deleteExpired(Instant now) {
        sessions.deleteExpiredBefore(now);
    }
}
