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
        // Nothing parseable came back. Passing here is deliberate: the auditor being unable
        // to speak must not lock a run that has already done its work — see
        // docs/NASIL-CALISIYOR.md §10, "Doğrulayıcı LLM'dir".
        if (node == null) {
            return new Verdict(true, "verifier could not parse a verdict, accepting",
                    response.totalTokens(), response.costUsd());
        }
        // JSON, but no verdict in it. That is not the same thing at all: the schema above
        // declares "pass" required, so a model that answers {"reason": "mesaj hiçbir bulgu
        // taşımıyor"} has given a negative judgement and left out the field that carries it.
        // Reading that as a pass told the user "doğrulandı" over the auditor's own objection.
        if (!node.has("pass")) {
            String said = node.path("reason").asText("").trim();
            return new Verdict(false, said.isEmpty()
                    ? "doğrulayıcı bir yargı vermedi"
                    : "doğrulayıcı bir yargı vermedi: " + said,
                    response.totalTokens(), response.costUsd());
        }
        boolean pass = node.path("pass").asBoolean(true);
        String reason = node.path("reason")
                .asText(pass ? "sonuç hedefe uygun" : "sonuç hedefi karşılamıyor");
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
