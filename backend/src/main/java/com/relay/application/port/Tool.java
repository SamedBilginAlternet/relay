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

    /**
     * Fills in what the connection already knows, before the parameters are shown for
     * approval and before any guard inspects them.
     *
     * <p>A configured default is worth nothing if the tool never reaches for it: Slack had
     * {@code defaultChannel = #all-samed} stored while a run failed on a channel the model
     * had left as a placeholder. Implementations must return the parameters unchanged when
     * they have nothing to add.
     */
    default JsonNode withDefaults(JsonNode params, Connection connection) {
        return params;
    }

    /** {@code jira} for {@code jira.updateIssue}. */
    default String provider() {
        int dot = name().indexOf('.');
        return dot > 0 ? name().substring(0, dot) : name();
    }
}
