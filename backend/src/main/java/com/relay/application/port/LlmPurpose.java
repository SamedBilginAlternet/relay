package com.relay.application.port;

/** Why the orchestrator is calling the model. */
public final class LlmPurpose {

    public static final String PLAN = "plan";
    public static final String TOOL_PARAMS = "tool_params";
    public static final String VERIFY = "verify";
    public static final String SUMMARIZE = "summarize";
    /** Daily brief: classify an item and propose one-click actions. */
    public static final String INSIGHT = "insight";
    /** Ask: a natural-language question → a Gmail search query. */
    public static final String MAIL_QUERY = "mail_query";
    /** Ask: found mails → a sourced Turkish answer. */
    public static final String MAIL_ANSWER = "mail_answer";
    /** Daily brief: one paragraph, an ordered priority list and one piece of advice. */
    public static final String DIGEST = "digest";

    private LlmPurpose() {
    }
}
