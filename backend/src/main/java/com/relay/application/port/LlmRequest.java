package com.relay.application.port;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single completion request.
 *
 * @param purpose  what the caller wants — see {@link LlmPurpose}. Drives the stub's deterministic branch.
 * @param system   system prompt
 * @param user     user prompt
 * @param schema   optional JSON schema the answer must satisfy (JSON mode)
 * @param context  free-form context object the stub can use (goal, step, previous results)
 */
public record LlmRequest(String purpose, String system, String user, JsonNode schema,
                         Object context, double temperature, int maxTokens) {

    public static LlmRequest of(String purpose, String system, String user, JsonNode schema, Object context) {
        return new LlmRequest(purpose, system, user, schema, context, 0.2, 1400);
    }
}
