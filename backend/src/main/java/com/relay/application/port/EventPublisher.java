package com.relay.application.port;

import java.util.UUID;

/** Pushes orchestration events towards whoever is watching (SSE in production). */
public interface EventPublisher {

    void publish(UUID runId, RunEvent event);
}
