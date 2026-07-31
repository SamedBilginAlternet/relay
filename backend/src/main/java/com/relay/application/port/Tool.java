package com.relay.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;

/**
 * The extension point. A new integration is one class implementing this interface;
 * the orchestrator never changes.
 */
public interface Tool {

    /** Fully qualified, e.g. {@code jira.updateIssue}. */
    String name();

    /** What the tool does — this is what the LLM reads when choosing. */
    String description();

    /** JSON Schema for {@code params}. Validated before execution. */
    JsonNode schema();

    RiskLevel risk();

    ToolResult execute(JsonNode params, Connection connection);

    /** {@code jira} for {@code jira.updateIssue}. */
    default String provider() {
        int dot = name().indexOf('.');
        return dot > 0 ? name().substring(0, dot) : name();
    }
}
