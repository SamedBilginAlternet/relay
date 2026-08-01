package com.relay.application.orchestrator;

import com.relay.application.orchestrator.RunService.SuggestionContext;
import java.util.Locale;

/**
 * Turns "the button that was pressed" into "the job that was asked for".
 *
 * <p>The goal of a run started from the Bugün screen used to be the label on the button, so
 * three live runs read {@code Cevap yaz}, {@code Review iste}, {@code İlerlemeyi güncelle} —
 * none of which says which mail, which pull request or which record. Everything downstream
 * reads that one sentence: the specialist writes the parameters from it, the grounding check
 * looks for the record key in it, and the user reads it in the history list.
 *
 * <p>So the sentence names the item and stops there. It is prompt text on every model call
 * of the run, which makes every extra character a recurring cost: the title, the sender and
 * one line of summary earn their place, the mail's body does not — that is what a read step
 * is for.
 */
final class SuggestionGoal {

    private SuggestionGoal() {
    }

    // Caps rather than a single total limit: each part is clipped where a reader would
    // still recognise it, so a runaway summary can never squeeze out the title.
    private static final int MAX_LABEL = 120;
    private static final int MAX_HANDLE = 60;
    private static final int MAX_TITLE = 160;
    private static final int MAX_FROM = 80;
    private static final int MAX_SUMMARY = 280;
    private static final int MAX_URL = 200;

    /** The short imperative that names the step in the timeline — never the long sentence. */
    static String stepTitle(String label, String toolName) {
        return label == null || label.isBlank()
                ? "Bugün önerisi: " + toolName
                : clip(oneLine(label), MAX_LABEL);
    }

    /**
     * {@code "Taslak cevap yaz — Gmail maili \"Sprint demosu…\" (Ayşe Demir). Özet: …"}
     *
     * <p>With no context this is the label and nothing more, which is exactly what a client
     * that does not send one used to get.
     */
    static String of(String label, String toolName, SuggestionContext context) {
        String base = stepTitle(label, toolName);
        if (context == null || context.empty()) {
            return base;
        }

        String source = source(context);
        String handle = handle(context, source);
        String title = clip(oneLine(context.title()), MAX_TITLE);
        String from = clip(oneLine(context.from()), MAX_FROM);
        String summary = clip(oneLine(context.summary()), MAX_SUMMARY);
        String url = clip(oneLine(context.url()), MAX_URL);

        StringBuilder sentence = new StringBuilder();
        String kind = kindLabel(source);
        if (!kind.isEmpty()) {
            sentence.append(kind);
        }
        if (!handle.isEmpty()) {
            append(sentence, handle);
        }
        if (!title.isEmpty()) {
            append(sentence, '"' + title + '"');
        }
        if (!from.isEmpty()) {
            append(sentence, "(" + from + ")");
        }
        if (sentence.length() == 0) {
            // Nothing nameable came through — an empty "—" clause would only add noise.
            return base;
        }

        StringBuilder goal = new StringBuilder(base).append(" — ").append(sentence).append('.');
        if (!summary.isEmpty()) {
            goal.append(" Özet: ").append(summary);
            if (!endsSentence(summary)) {
                goal.append('.');
            }
        }
        if (!url.isEmpty()) {
            goal.append(" Bağlantı: ").append(url);
        }
        return goal.toString();
    }

    /** What the item is, in the words the screen already uses for it. */
    private static String kindLabel(String source) {
        return switch (source) {
            case "gmail" -> "Gmail maili";
            case "jira" -> "Jira kaydı";
            case "github" -> "GitHub kaydı";
            case "calendar" -> "Takvim etkinliği";
            // An unknown source names nothing; the title still does.
            default -> "";
        };
    }

    /**
     * {@code jira:KAN-42} → {@code KAN-42}, {@code github-pr:acme/pay#12} → {@code acme/pay#12}.
     *
     * <p>Only for the sources whose items have a name of their own. A mail's handle is an
     * opaque Gmail id: it would put a meaningless token in front of the subject and, worse,
     * would read like something the model could cite.
     */
    private static String handle(SuggestionContext context, String source) {
        if (!"jira".equals(source) && !"github".equals(source)) {
            return "";
        }
        String itemId = context.itemId() == null ? "" : context.itemId().trim();
        int colon = itemId.indexOf(':');
        return colon < 0 ? "" : clip(oneLine(itemId.substring(colon + 1)), MAX_HANDLE);
    }

    /** The declared source, or the one the item id starts with — {@code github-pr:…}. */
    private static String source(SuggestionContext context) {
        String declared = context.source() == null ? "" : context.source().trim().toLowerCase(Locale.ROOT);
        if (!declared.isEmpty()) {
            return declared;
        }
        String itemId = context.itemId() == null ? "" : context.itemId().trim().toLowerCase(Locale.ROOT);
        int colon = itemId.indexOf(':');
        String prefix = colon < 0 ? "" : itemId.substring(0, colon);
        int dash = prefix.indexOf('-');
        return dash < 0 ? prefix : prefix.substring(0, dash);
    }

    private static void append(StringBuilder sentence, String part) {
        if (sentence.length() > 0) {
            sentence.append(' ');
        }
        sentence.append(part);
    }

    private static boolean endsSentence(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…';
    }

    /** A goal is one line: a pasted mail signature must not turn it into a paragraph. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1).trim() + "…";
    }
}
