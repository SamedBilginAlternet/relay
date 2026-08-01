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

    /**
     * The auditor's judgement, and what asking for it cost.
     *
     * <p>Verification is one of the jobs routed to the small model, so this is exactly where
     * the saving shows up — and it only shows up if the model that answered survives the trip
     * back to the coordinator, which is the thing that writes cost down.
     */
    public record Verdict(boolean pass, String reason, long tokens, double costUsd,
                          Double premiumCostUsd, String model, boolean judged) {

        public Verdict(boolean pass, String reason, long tokens, double costUsd) {
            this(pass, reason, tokens, costUsd, null, null, true);
        }

        static Verdict of(boolean pass, String reason, LlmResponse response) {
            return new Verdict(pass, reason, response.totalTokens(), response.costUsd(),
                    response.premiumCostUsd(), response.model(), true);
        }

        /**
         * The auditor said nothing that could be read as a judgement.
         *
         * <p>The step is let through — see the comment at the call site — but it is not
         * verified, and the transcript is not allowed to say it was. Live on 2026-08-01,
         * with every Groq key at its daily wall, the line read
         * {@code Adım 1 doğrulandı: verifier could not parse a verdict, accepting}: an
         * English apology, printed under a Turkish word meaning the opposite of what had
         * happened.
         */
        static Verdict unjudged(LlmResponse response) {
            return new Verdict(true, "denetçi bir yargı veremedi", response.totalTokens(),
                    response.costUsd(), response.premiumCostUsd(), response.model(), false);
        }
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
        /*
          Nothing parseable came back. Letting the step through is deliberate and unchanged:
          the auditor being unable to speak must not lock a run that has already done its
          work — docs/NASIL-CALISIYOR.md §10, "Doğrulayıcı LLM'dir".

          What changed is what we then say about it. This used to be reported to the reader
          as "Adım 1 doğrulandı", which is the auditor's silence written up as its approval.
          On the fallback provider that silence is not rare — it is the normal case, because
          a reasoning model will not hold the verdict schema — so the product was printing
          "verified" over every step of every run it could not check.
        */
        if (node == null) {
            return Verdict.unjudged(response);
        }
        // JSON, but no verdict in it. That is not the same thing at all: the schema above
        // declares "pass" required, so a model that answers {"reason": "mesaj hiçbir bulgu
        // taşımıyor"} has given a negative judgement and left out the field that carries it.
        // Reading that as a pass told the user "doğrulandı" over the auditor's own objection.
        if (!node.has("pass")) {
            String said = node.path("reason").asText("").trim();
            return Verdict.of(false, said.isEmpty()
                    ? "doğrulayıcı bir yargı vermedi"
                    : "doğrulayıcı bir yargı vermedi: " + said, response);
        }
        boolean pass = node.path("pass").asBoolean(true);
        String reason = node.path("reason")
                .asText(pass ? "sonuç hedefe uygun" : "sonuç hedefi karşılamıyor");
        return Verdict.of(pass, reason, response);
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
