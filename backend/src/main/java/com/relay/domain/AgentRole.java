package com.relay.domain;

/** Names of the crew members. Used as {@code fromAgent} / {@code toAgent}. */
public final class AgentRole {

    public static final String USER = "user";
    public static final String PLANNER = "planner";
    public static final String COORDINATOR = "coordinator";
    public static final String VERIFIER = "verifier";
    public static final String POLICY = "policy";
    public static final String COST = "cost";

    private AgentRole() {
    }

    /** "jira.updateIssue" -> "jira-agent" */
    public static String toolAgent(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "generalist-agent";
        }
        return ToolPolicy.providerOf(toolName) + "-agent";
    }
}
