package com.relay.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

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
     * The ceiling for the answers that carry a whole structure rather than a sentence.
     *
     * <p>WHY THIS IS BIGGER. A thinking model spends output budget reasoning before it
     * writes anything, and at 1400 it runs out mid-JSON. Measured directly against
     * gemini-3.6-flash on 2026-08-01 with one digest-shaped prompt:
     *
     * <pre>
     *   max_tokens=1400  finish=length  thought=1095  written=301  → truncated, unparseable
     *   max_tokens=3600  finish=stop    thought= 784  written=379  → valid JSON
     * </pre>
     *
     * <p>The same failure had already been measured on the plan: three consecutive
     * multi-source goals came back with no step array at all, after calls that had spent
     * 4 091 tokens getting nowhere. On the brief it cost the whole digest — the day's
     * written summary and every row's "neden şimdi" sentence — which the backend then
     * correctly omitted rather than shipping filler, so the feature simply went dark.
     *
     * <p>Raised again on 2026-08-02: the same signature (real tokens billed, a provider
     * genuinely answered, {@code Planner.readable()} still rejects the content) showed up
     * on DeepSeek once it started answering at all — a fix that had just landed for a
     * different bug (the JDK HttpClient's HTTP/2 implementation failing every POST to
     * DeepSeek's endpoint). Every step's title and params live inside one JSON array, and
     * whether the model reasons before writing or simply writes at length, the failure
     * looks identical: {@code readable()} sees no array at all.
     *
     * <p>It is a ceiling, not a spend: the only cost of raising it is a model that decides
     * to fill it. Kept off the short answers — a verdict, a three-sentence summary, one
     * provider query — because those never came close to it.
     */
    private static final int LONG_ROOM = 6000;

    /** The answers that are a structure: several items, each with fields of its own. */
    private static final Set<String> LONG = Set.of(
            LlmPurpose.PLAN, LlmPurpose.DIGEST, LlmPurpose.INSIGHT, LlmPurpose.ASK_ANSWER);

    public static LlmRequest of(String purpose, String system, String user, JsonNode schema, Object context) {
        // `Set.of` throws on a null lookup, and an unnamed purpose is a real caller: it is
        // the case `PurposeRoutingTest` holds, where an unclassified job must still get an
        // answer rather than an exception.
        return new LlmRequest(purpose, system, user, schema, context, 0.2,
                purpose != null && LONG.contains(purpose) ? LONG_ROOM : ROOM);
    }
}
