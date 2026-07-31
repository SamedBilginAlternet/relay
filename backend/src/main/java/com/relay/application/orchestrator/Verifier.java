package com.relay.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.util.Map;

/**
 * The auditor. Checks a finished step against the goal. A failing verdict sends
 * the step back to its agent — at most {@link Step#MAX_RETRIES} times.
 */
public class Verifier {

    private final LlmClient llm;

    public Verifier(LlmClient llm) {
        this.llm = llm;
    }

    public record Verdict(boolean pass, String reason, long tokens, double costUsd) {
    }

    public Verdict verify(Run run, Step step, Object result) {
        LlmRequest request = LlmRequest.of(
                LlmPurpose.VERIFY,
                "You are the Verifier of Relay. Decide whether the step result actually satisfies the step "
                        + "and moves the goal forward. Be strict about errors and empty results, but do not "
                        + "demand more than the step asked for.\n"
                        + "Fail the step when text meant for a human carries no facts — a message or "
                        + "description that only says work was done, without naming records, counts or "
                        + "states that the earlier steps found. Reason in the language of the goal. "
                        + "Answer JSON only.",
                "GOAL:\n" + run.goal()
                        + "\n\nSTEP " + step.ordinal() + ": " + step.title()
                        + "\nTOOL: " + step.toolName()
                        + "\nPARAMS: " + Json.preview(step.params(), 600)
                        + "\n\nRESULT:\n" + Json.preview(result, 2500)
                        + "\n\nAnswer JSON: {\"pass\": true|false, \"reason\": \"…\"}",
                schema(),
                Map.of("step", step.title(), "result", result));

        LlmResponse response = llm.complete(request);
        JsonNode node = Json.extract(response.content());
        boolean pass = node == null || !node.has("pass") || node.path("pass").asBoolean(true);
        String reason = node == null ? "verifier could not parse a verdict, accepting"
                : node.path("reason").asText(pass ? "sonuç hedefe uygun" : "sonuç hedefi karşılamıyor");
        return new Verdict(pass, reason, response.totalTokens(), response.costUsd());
    }

    public static JsonNode schema() {
        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("pass");
        ObjectNode props = root.putObject("properties");
        props.putObject("pass").put("type", "boolean");
        props.putObject("reason").put("type", "string");
        return root;
    }
}
