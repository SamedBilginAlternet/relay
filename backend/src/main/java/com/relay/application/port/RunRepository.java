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

    /**
     * Newest first, one status only.
     *
     * <p>The top bar counts the runs waiting on a person and sends the reader to Geçmiş,
     * which showed whichever of them happened to land on the first page — 29 counted, 3
     * shown. Scanning pages client-side to rebuild that set costs a request per page and
     * still races anything that finishes meanwhile; the database already knows.
     */
    List<Run> findByStatus(com.relay.domain.RunStatus status, int page, int size);

    long count();

    long countByStatus(com.relay.domain.RunStatus status);
}
