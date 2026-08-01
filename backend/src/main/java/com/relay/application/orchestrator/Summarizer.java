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
                            // HEDEF is what was ASKED FOR; ADIMLAR is what HAPPENED. Live, a
                            // run whose only step was one gmail.search closed with "#42 ve #43
                            // üzerinde çalışıldı ... takvim etkinliği tamamlandı" — three
                            // completion claims read straight off the goal. A model handed a
                            // four-part goal and one step will narrate the goal unless it is
                            // told, in as many words, that the goal is not evidence.
                            + " HEDEF istenen iştir, ADIMLAR olan iştir. Yalnızca ADIMLAR'da"
                            + " çalışmış bir adımın yaptığı işi anlat. Hedefte olup adımı"
                            + " olmayan bir iş için 'yapıldı', 'incelendi', 'tamamlandı' deme —"
                            + " o işe hiç girilmediğini söyle."
                            // A skipped step is the run's own finding that there was nothing
                            // to do. That finding is the summary's best sentence — "koşulu
                            // sağlayan mail yoktu, kayıt açılmadı" — and without this line
                            // the model either ignores the step or narrates it as work.
                            + " 'skipped' bir adım bilinçli atlandı demektir: gerekçesindeki"
                            + " nedeni söyle ('… bulunmadığı için … yapılmadı'), o işi"
                            + " yapılmış gibi anlatma."
                            + (failed ? " Akış tamamlanamadı: hangi adımda neden durduğunu tek cümleyle söyle."
                                      : " Adım adım anlatma, sonucu söyle.")
                            + " Sadece düz metin yaz, JSON yazma.",
                    "HEDEF:\n" + run.goal() + "\n\nADIMLAR:\n" + steps(run),
                    null,
                    Map.of("goal", run.goal()));

            LlmResponse response = llm.complete(request);
            if (response.fallback()) {
                // Degraded *during* the call: the last key ran out after the check above.
                // Live, that closed a run the user had just rejected with the stub's
                // "Sonuç bulunamadı: <the goal>" — filler, and wrong about what happened.
                return null;
            }
            String text = clean(response.content());
            if (text == null || invents(text, run)) {
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
            } else if (step.status() == StepStatus.SKIPPED && step.skipReason() != null) {
                // Spelled out rather than left as the raw skip record: the reason is the
                // evidence the closing line quotes for why nothing was written.
                line.append(" atlandı: ").append(step.skipReason());
            } else if (step.result() != null) {
                line.append(" sonuç: ").append(Json.preview(step.result(), MAX_RESULT_CHARS));
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /** {@code KAN-42}, {@code REL-7} — the shape of a record key in the text we show. */
    private static final java.util.regex.Pattern RECORD_KEY =
            java.util.regex.Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");

    /** {@code #42} — how a summary refers to an issue or a pull request. */
    private static final java.util.regex.Pattern ISSUE_REF =
            java.util.regex.Pattern.compile("#(\\d{1,7})\\b");

    /**
     * What the run actually produced — nothing about what it was asked to produce.
     *
     * <p>Deliberately NOT {@code run.goal()} and NOT the step titles. Both are the request,
     * not the outcome, and a guard that accepts either lets the request license a claim that
     * the work was done. Live on 2026-08-01: a run whose only step was one {@code
     * gmail.search} closed with "#42 numaralı login yönlendirme hatası ve #43 numaralı README
     * kurulum PR'ı üzerinde çalışıldı ... 'Hackathon takvimi incele' tamamlandı". Every one of
     * those came out of the goal. GitHub was never called. The calendar was never read.
     *
     * <p>Step titles are excluded for the same reason and it is not hypothetical either: a
     * planner that writes "GitHub #42'yi incele" and never runs it would otherwise ground the
     * sentence saying it was examined.
     *
     * <p>A failed step's error is included: "#42'ye yorum eklenemedi" is a true sentence about
     * a real attempt, and the error is where the identifier is.
     */
    private static String evidence(Run run) {
        StringBuilder out = new StringBuilder();
        for (Step step : run.steps()) {
            if (step.status() == StepStatus.FAILED && step.error() != null) {
                out.append(step.error()).append('\n');
            } else if (step.result() != null) {
                out.append(Json.preview(step.result(), MAX_RESULT_CHARS)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Is this identifier one the run actually handled?
     *
     * <p>An issue is written {@code #42} in prose and {@code "number": 42} or {@code
     * .../issues/42} in a provider's JSON, so those three forms all count. A bare {@code 42}
     * anywhere in a payload does not: token counts, sizes and ids would make almost any
     * number look grounded, and a guard that never fires is not a guard.
     */
    private static boolean seen(String evidence, String token, String digits) {
        if (evidence.contains(token)) {
            return true;
        }
        return digits != null && (evidence.contains("\"number\":" + digits)
                || evidence.contains("\"number\": " + digits)
                || evidence.contains("/" + digits));
    }

    /**
     * Does the closing line name something the run never touched?
     *
     * <p>Live: a search returned KAN-11 down to KAN-1 and the summary reported them as
     * "KAN-11, KAN-12 ve KAN-13". Neither of the last two exists. The grounding guard that
     * stops an invented key from reaching a provider covers <em>parameters</em>; this text
     * goes straight to the person, unchecked, and it is the last thing they read.
     *
     * <p>The whole product rests on not making things up, so a summary that does gets
     * dropped rather than corrected — the cost line still closes the run honestly.
     */
    private static boolean invents(String text, Run run) {
        String evidence = evidence(run);
        java.util.regex.Matcher keys = RECORD_KEY.matcher(text);
        while (keys.find()) {
            if (!seen(evidence, keys.group(), null)) {
                LOG.log(Level.WARNING, "closing summary named {0}, which the run never saw", keys.group());
                return true;
            }
        }
        java.util.regex.Matcher refs = ISSUE_REF.matcher(text);
        while (refs.find()) {
            if (!seen(evidence, refs.group(), refs.group(1))) {
                LOG.log(Level.WARNING, "closing summary named {0}, which the run never saw", refs.group());
                return true;
            }
        }
        return false;
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
