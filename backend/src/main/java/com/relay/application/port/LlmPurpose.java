package com.relay.application.port;

/** Why the orchestrator is calling the model. */
public final class LlmPurpose {

    public static final String PLAN = "plan";
    public static final String TOOL_PARAMS = "tool_params";
    public static final String VERIFY = "verify";
    public static final String SUMMARIZE = "summarize";
    /** Daily brief: classify an item and propose one-click actions. */
    public static final String INSIGHT = "insight";

    private LlmPurpose() {
    }
}
