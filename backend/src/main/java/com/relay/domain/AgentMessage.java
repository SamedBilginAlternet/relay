package com.relay.domain;

import java.time.Instant;
import java.util.UUID;

/** One line of agent-to-agent chatter. Visible on the timeline. */
public record AgentMessage(UUID id, UUID runId, UUID stepId, String fromAgent, String toAgent,
                           String content, Instant createdAt) {

    public static AgentMessage of(UUID runId, UUID stepId, String from, String to, String content, Instant now) {
        return new AgentMessage(UUID.randomUUID(), runId, stepId, from, to, content, now);
    }
}
