package com.relay.application.orchestrator;

import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Answers the only question the user actually asked: what happened?
 *
 * <p>A finished run used to end on bookkeeping — "Akış bitti: done · 1.018 token · $0,0012" —
 * leaving the reader to reconstruct the outcome from raw tool payloads. That breaks the rule
 * the whole product is built on (DESIGN.md §6): if you cannot answer "what is going on and
 * why" by looking at the screen, the screen is wrong.
 *
 * <p>Everything here degrades to silence: a failed or garbled model call returns
 * {@code null} and the run finishes exactly as it did before.
 */
public class Summarizer {

    private static final Logger LOG = System.getLogger(Summarizer.class.getName());
    private static final int MAX_RESULT_CHARS = 900;

    private final LlmClient llm;

    public Summarizer(LlmClient llm) {
        this.llm = llm;
    }

    /** Two or three sentences in Turkish, or {@code null} when the model cannot help. */
    public Outcome summarise(Run run, boolean failed) {
        if (llm.degraded()) {
            // The fallback writes template filler ("Adımlar yürütüldü; ayrıntılar zaman
            // çizelgesinde") which reads like the product has nothing to say. Better to
            // close on the cost line than to fake an answer.
            return null;
        }
        try {
            LlmRequest request = LlmRequest.of(
                    LlmPurpose.SUMMARIZE,
                    "Relay'in kapanış özetini yazıyorsun. Kullanıcıya Türkçe, en fazla üç cümlede"
                            + " NE OLDUĞUNU söyle: hangi kayıtlar, hangi kanal, kaç tane."
                            + " Sayıları ve anahtarları sonuçlardan al, uydurma."
                            + (failed ? " Akış tamamlanamadı: hangi adımda neden durduğunu tek cümleyle söyle."
                                      : " Adım adım anlatma, sonucu söyle.")
                            + " Sadece düz metin yaz, JSON yazma.",
                    "HEDEF:\n" + run.goal() + "\n\nADIMLAR:\n" + steps(run),
                    null,
                    Map.of("goal", run.goal()));

            LlmResponse response = llm.complete(request);
            String text = clean(response.content());
            if (text == null) {
                return null;
            }
            return new Outcome(text, response.totalTokens(), response.costUsd());
        } catch (RuntimeException e) {
            // The run is already finished; a missing summary must never change its status.
            LOG.log(Level.WARNING, "closing summary unavailable: {0}", e.getMessage());
            return null;
        }
    }

    public record Outcome(String text, long tokens, double costUsd) {
    }

    private static String steps(Run run) {
        List<String> lines = new ArrayList<>();
        for (Step step : run.steps()) {
            StringBuilder line = new StringBuilder()
                    .append(step.ordinal()).append(") ").append(step.title())
                    .append(" [").append(step.toolName() == null ? "düşünme" : step.toolName())
                    .append(" · ").append(step.status().wire()).append("]");
            if (step.status() == StepStatus.FAILED && step.error() != null) {
                line.append(" hata: ").append(step.error());
            } else if (step.result() != null) {
                line.append(" sonuç: ").append(Json.preview(step.result(), MAX_RESULT_CHARS));
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /** Guards against an empty answer or a model that replied with JSON anyway. */
    private static String clean(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return null;
        }
        return text.isEmpty() ? null : text;
    }
}
