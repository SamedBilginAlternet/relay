package com.relay.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, UUID> {

    Optional<SessionEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from SessionEntity s where s.tokenHash = ?1")
    void deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from SessionEntity s where s.expiresAt <= ?1")
    void deleteExpiredBefore(Instant now);
}
