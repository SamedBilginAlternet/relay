package com.relay.application.port;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One SSE frame. {@code type} is the event name from ARCHITECTURE §5:
 * run.planned, step.started, step.awaiting, step.finished, agent.message, run.cost, run.finished.
 */
public record RunEvent(String type, Map<String, Object> data) {

    public static final String RUN_PLANNED = "run.planned";
    public static final String STEP_STARTED = "step.started";
    public static final String STEP_AWAITING = "step.awaiting";
    public static final String STEP_FINISHED = "step.finished";
    public static final String AGENT_MESSAGE = "agent.message";
    public static final String RUN_COST = "run.cost";
    public static final String RUN_FINISHED = "run.finished";

    public static RunEvent of(String type, Map<String, Object> data) {
        return new RunEvent(type, data == null ? Map.of() : new LinkedHashMap<>(data));
    }
}
