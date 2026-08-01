package com.relay.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunEntityRepository extends JpaRepository<RunEntity, UUID> {

    /** The status column holds the wire value, so callers pass {@code RunStatus.wire()}. */
    org.springframework.data.domain.Page<RunEntity> findByStatus(
            String status, org.springframework.data.domain.Pageable pageable);

    long countByStatus(String status);
}
