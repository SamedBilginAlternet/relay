package com.relay.application.view;

import com.relay.domain.AgentMessage;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the wire shape (ARCHITECTURE §5). SSE frames and REST
 * responses are built from the same functions, so they can never drift apart.
 * camelCase keys, UUIDs as strings, timestamps as ISO-8601.
 */
public final class Views {

    private Views() {
    }

    public static Map<String, Object> step(Step step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.id().toString());
        map.put("runId", step.runId().toString());
        map.put("ordinal", step.ordinal());
        map.put("title", step.title());
        map.put("role", step.role());
        map.put("toolName", step.toolName());
        map.put("params", step.params());
        map.put("status", step.status().wire());
        map.put("decision", step.decision() == null ? null : step.decision().wire());
        map.put("rejectReason", step.rejectReason());
        map.put("result", step.result());
        map.put("error", step.error());
        map.put("tokens", step.tokens());
        map.put("costUsd", step.costUsd());
        map.put("attempts", step.attempts());
        map.put("startedAt", iso(step.startedAt()));
        map.put("finishedAt", iso(step.finishedAt()));
        return map;
    }

    public static Map<String, Object> message(AgentMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", message.id().toString());
        map.put("runId", message.runId().toString());
        map.put("stepId", message.stepId() == null ? null : message.stepId().toString());
        map.put("fromAgent", message.fromAgent());
        map.put("toAgent", message.toAgent());
        map.put("content", message.content());
        map.put("createdAt", iso(message.createdAt()));
        return map;
    }

    public static Map<String, Object> run(Run run) {
        Map<String, Object> map = runSummary(run);
        List<Map<String, Object>> steps = new ArrayList<>();
        run.steps().forEach(s -> steps.add(step(s)));
        List<Map<String, Object>> messages = new ArrayList<>();
        run.messages().forEach(m -> messages.add(message(m)));
        map.put("steps", steps);
        map.put("messages", messages);
        return map;
    }

    public static Map<String, Object> runSummary(Run run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.id().toString());
        map.put("goal", run.goal());
        map.put("status", run.status().wire());
        map.put("costTokens", run.costTokens());
        map.put("costUsd", run.costUsd());
        map.put("budgetUsd", run.budgetUsd());
        map.put("budgetOverridden", run.budgetOverridden());
        map.put("stepCount", run.steps().size());
        map.put("createdAt", iso(run.createdAt()));
        map.put("finishedAt", iso(run.finishedAt()));
        return map;
    }

    public static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
