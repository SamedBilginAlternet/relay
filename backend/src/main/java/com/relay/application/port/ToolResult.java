package com.relay.application.port;

import com.fasterxml.jackson.databind.JsonNode;

/** Outcome of a single tool call. */
public record ToolResult(boolean ok, JsonNode data, String error, long durationMs, String mode) {

    public static ToolResult ok(JsonNode data, long durationMs, String mode) {
        return new ToolResult(true, data, null, durationMs, mode);
    }

    public static ToolResult error(String error, long durationMs, String mode) {
        return new ToolResult(false, null, error, durationMs, mode);
    }
}
