package com.relay.application.port;

import java.util.UUID;

/** Pushes orchestration events towards whoever is watching (SSE in production). */
public interface EventPublisher {

    void publish(UUID runId, RunEvent event);

    /**
     * The run reached a terminal state: there will be no further frames, ever.
     *
     * <p>Without this the transport had no way of knowing an ending had happened, so a
     * finished run kept its connection open for the full half-hour timeout, sent a keepalive
     * every twenty seconds, and then — because a timeout looks exactly like a dropped
     * connection from the browser — was reconnected to and replayed from the beginning. A
     * finished flow that redraws itself every thirty minutes is not a live view of anything.
     */
    void closed(UUID runId);
}
