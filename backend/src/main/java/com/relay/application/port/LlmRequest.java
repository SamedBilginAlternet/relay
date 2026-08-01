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

    /**
     * The ordinary ceiling. Enough for a verdict, a summary, one tool's parameters.
     *
     * <p>It is a ceiling and not a spend — you pay for what is generated — so the only
     * cost of raising it is a model that decides to fill it.
     */
    private static final int ROOM = 1400;

    /**
     * The ceiling for an answer that has to carry a whole plan.
     *
     * <p>WHY THIS IS BIGGER. A reasoning model spends its output budget thinking before it
     * writes anything, and at 1400 it can run out before the JSON starts. Measured live on
     * 2026-08-01 with every Groq key at its daily wall and the paid tier answering: three
     * consecutive multi-source goals came back with no parseable plan at all — not a bad
     * plan, no array in the answer — and the planning call had spent 4 091 tokens getting
     * there. The plan is also simply the longest structured answer this product asks for:
     * up to eight steps, each with a title and a parameter object.
     */
    private static final int PLAN_ROOM = 3600;

    public static LlmRequest of(String purpose, String system, String user, JsonNode schema, Object context) {
        return new LlmRequest(purpose, system, user, schema, context, 0.2,
                LlmPurpose.PLAN.equals(purpose) ? PLAN_ROOM : ROOM);
    }
}
