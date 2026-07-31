package com.relay.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The specialist. Knows one tool per step: finalises the parameters against that
 * tool's schema (using earlier step results as context) and calls it.
 *
 * <p>A step without a tool is a reasoning step and is answered by the LLM directly.
 */
public class ToolAgent {

    private final ToolRegistry tools;
    private final LlmClient llm;
    private final ConnectionRepository connections;
    private final AgentJournal journal;
    private final Clock clock;

    public ToolAgent(ToolRegistry tools, LlmClient llm, ConnectionRepository connections,
                     AgentJournal journal, Clock clock) {
        this.tools = tools;
        this.llm = llm;
        this.connections = connections;
        this.journal = journal;
        this.clock = clock;
    }

    public StepOutcome execute(Run run, Step step) {
        String agent = step.role() == null ? AgentRole.toolAgent(step.toolName()) : step.role();
        journal.say(run, step.id(), AgentRole.COORDINATOR, agent,
                "Adım " + step.ordinal() + " sende: " + step.title());

        if (step.toolName() == null || step.toolName().isBlank()) {
            return reason(run, step, agent);
        }

        Tool tool = tools.find(step.toolName()).orElse(null);
        if (tool == null) {
            return StepOutcome.failed("unknown tool: " + step.toolName(), 0, 0);
        }

        ParamOutcome params = finaliseParams(run, step, tool);
        if (!params.valid()) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                    "Parametreler şemaya uymadı: " + params.error());
            return StepOutcome.failed("parameter validation failed: " + params.error(),
                    params.tokens(), params.costUsd());
        }
        step.params(Json.toMap(params.params()));

        Connection connection = connections.findByProvider(tool.provider()).orElse(null);

        journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                tool.name() + " çağrılıyor: " + Json.preview(step.params(), 240));

        ToolResult result;
        try {
            result = tool.execute(params.params(), connection);
        } catch (RuntimeException e) {
            return StepOutcome.failed(tool.name() + " threw: " + e.getMessage(),
                    params.tokens(), params.costUsd());
        }

        if (!result.ok()) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                    tool.name() + " hata verdi: " + result.error());
            return StepOutcome.failed(result.error(), params.tokens(), params.costUsd());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool.name());
        payload.put("mode", result.mode());
        payload.put("durationMs", result.durationMs());
        payload.put("data", Json.toPlain(result.data()));

        journal.say(run, step.id(), agent, AgentRole.VERIFIER,
                tool.name() + " tamam (" + result.durationMs() + " ms), sonuç doğrulamaya gidiyor.");
        return StepOutcome.ok(payload, params.tokens(), params.costUsd());
    }

    // ---- reasoning step ---------------------------------------------------

    private StepOutcome reason(Run run, Step step, String agent) {
        LlmRequest request = LlmRequest.of(
                LlmPurpose.SUMMARIZE,
                "You are a Relay agent. Answer the step using the goal and the previous results. Be brief.",
                "GOAL:\n" + run.goal() + "\n\nSTEP: " + step.title()
                        + "\n\nPREVIOUS RESULTS:\n" + Json.preview(previousResults(run, step), 2000),
                null,
                Map.of("goal", run.goal(), "step", step.title()));
        LlmResponse response = llm.complete(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", null);
        payload.put("mode", "llm");
        payload.put("text", response.content());
        journal.say(run, step.id(), agent, AgentRole.VERIFIER, "Metin üretildi, doğrulamaya gidiyor.");
        return StepOutcome.ok(payload, response.totalTokens(), response.costUsd());
    }

    // ---- parameters -------------------------------------------------------

    private record ParamOutcome(boolean valid, JsonNode params, String error, long tokens, double costUsd) {
    }

    private ParamOutcome finaliseParams(Run run, Step step, Tool tool) {
        JsonNode draft = Json.toNode(step.params());
        if (!draft.isObject()) {
            draft = Json.object();
        }

        long tokens = 0;
        double cost = 0;
        JsonNode candidate = draft;

        SchemaValidator.Result draftCheck = SchemaValidator.validate(tool.schema(), draft);
        if (!draftCheck.valid()) {
            // Only spend a model call when the draft is not already good enough.
            LlmRequest request = LlmRequest.of(
                    LlmPurpose.TOOL_PARAMS,
                    "You are the " + tool.provider() + " specialist agent of Relay. "
                            + "Produce ONLY the JSON parameter object for the tool call. No prose.",
                    "GOAL:\n" + run.goal()
                            + "\n\nSTEP: " + step.title()
                            + "\n\nTOOL: " + tool.name() + " — " + tool.description()
                            + "\n\nPARAM SCHEMA:\n" + tool.schema().toString()
                            + "\n\nDRAFT PARAMS:\n" + draft
                            + "\n\nPREVIOUS RESULTS:\n" + Json.preview(previousResults(run, step), 2000)
                            + "\n\nProblems with the draft: " + draftCheck.message(),
                    tool.schema(),
                    Map.of("tool", tool.name(),
                            "draft", Json.toPlain(draft),
                            "goal", run.goal(),
                            "previous", previousResults(run, step)));
            LlmResponse response = llm.complete(request);
            tokens = response.totalTokens();
            cost = response.costUsd();
            JsonNode fromModel = Json.extract(response.content());
            if (fromModel != null && fromModel.isObject()) {
                candidate = merge(draft, fromModel);
            }
        }

        SchemaValidator.Result check = SchemaValidator.validate(tool.schema(), candidate);
        if (!check.valid()) {
            return new ParamOutcome(false, candidate, check.message(), tokens, cost);
        }
        return new ParamOutcome(true, candidate, null, tokens, cost);
    }

    /** Model output wins, draft fills the gaps. */
    private JsonNode merge(JsonNode draft, JsonNode override) {
        ObjectNode merged = Json.object();
        draft.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        override.fields().forEachRemaining(e -> {
            if (e.getValue() != null && !e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        return merged;
    }

    private List<Map<String, Object>> previousResults(Run run, Step step) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Step other : run.steps()) {
            if (other.ordinal() >= step.ordinal() || other.result() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", other.ordinal());
            item.put("title", other.title());
            item.put("tool", other.toolName());
            item.put("result", other.result());
            out.add(item);
        }
        return out;
    }
}
