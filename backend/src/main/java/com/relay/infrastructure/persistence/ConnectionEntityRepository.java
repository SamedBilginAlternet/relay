package com.relay.infrastructure.persistence;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionEntityRepository extends JpaRepository<ConnectionEntity, UUID> {

    List<ConnectionEntity> findAllByUserId(UUID userId);

    Optional<ConnectionEntity> findByUserIdAndProvider(UUID userId, String provider);
}
