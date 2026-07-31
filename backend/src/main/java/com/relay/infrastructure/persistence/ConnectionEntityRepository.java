package com.relay.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionEntityRepository extends JpaRepository<ConnectionEntity, UUID> {

    Optional<ConnectionEntity> findByProvider(String provider);
}
