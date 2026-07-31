package com.relay.application.port;

import com.relay.domain.Run;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository {

    Run save(Run run);

    Optional<Run> findById(UUID id);

    /** Newest first. */
    List<Run> findAll(int page, int size);

    long count();
}
