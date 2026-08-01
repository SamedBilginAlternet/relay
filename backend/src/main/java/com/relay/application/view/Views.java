package com.relay.application.view;

import com.relay.application.cost.CostMeter;
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
 * camelCase keys, UUIDs as strings, timestamps as ISO-8601, money through
 * {@link CostMeter#usd(Double)} so it never leaves here as {@code 3.82E-4}.
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
        map.put("costUsd", CostMeter.usd(step.costUsd()));
        // Which model answered, and what the same tokens would have cost on the strong one.
        // A cost with no model beside it cannot be read once the tier is chosen per job:
        // a cheap step and a step that got the cheap answer look identical. Both may be
        // null — no call has landed yet, or none of them could be priced that way.
        map.put("model", step.model());
        map.put("premiumCostUsd", CostMeter.usd(step.premiumCostUsd()));
        map.put("attempts", step.attempts());
        // Which question the gate is asking. Both pauses arrive as awaiting_approval, and a
        // screen that cannot tell them apart calls a spending limit a writing permission.
        map.put("pausedBy", step.pausedBy() == null ? null : step.pausedBy().wire());
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
        map.put("costUsd", CostMeter.usd(run.costUsd()));
        map.put("budgetUsd", CostMeter.usd(run.budgetUsd()));
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
