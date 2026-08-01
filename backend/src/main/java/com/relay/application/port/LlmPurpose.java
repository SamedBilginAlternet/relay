package com.relay.application.port;

/** Why the orchestrator is calling the model. */
public final class LlmPurpose {

    public static final String PLAN = "plan";
    public static final String TOOL_PARAMS = "tool_params";
    public static final String VERIFY = "verify";
    public static final String SUMMARIZE = "summarize";
    /** Daily brief: classify an item and propose one-click actions. */
    public static final String INSIGHT = "insight";
    /** Ask: a natural-language question → which READ tools to ask, and each one's query. */
    public static final String ASK_ROUTE = "ask_route";
    /** Ask: what those tools found → a sourced Turkish answer. */
    public static final String ASK_ANSWER = "ask_answer";
    /** Daily brief: one paragraph, an ordered priority list and one piece of advice. */
    public static final String DIGEST = "digest";

    /**
     * The jobs a small model is enough for, unless {@code app.llm.small-purposes} says otherwise.
     *
     * <p>These three ask for a shape, not a judgement: a yes/no with one sentence behind it,
     * three sentences of summary, one provider query. The rest — a plan, a tool's parameters,
     * a digest, a classification, an answer a person will act on — decide *what gets written
     * where* or *what the user is told is true*, and getting one of those wrong is not a
     * cheaper mistake for having been made cheaply.
     *
     * <p>Anything not named here goes to the strong model, including a purpose nobody has
     * added to this class yet. Unknown means expensive, never wrong.
     */
    public static final java.util.Set<String> DEFAULT_SMALL = java.util.Set.of(VERIFY, SUMMARIZE, ASK_ROUTE);

    private LlmPurpose() {
    }
}
