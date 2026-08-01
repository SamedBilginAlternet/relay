package com.relay.application.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Widens a Gmail query that found nothing, once.
 *
 * <p>The router writes the query from the question, and a Turkish question about an English
 * mailbox produces a Turkish {@code subject:} filter: {@code (from:anthropic) subject:(fatura
 * OR ödeme OR makbuz) newer_than:30d} matches nothing, while the receipt sits in the inbox
 * under "Your receipt from Anthropic, PBC". Asked twice, the same question answered "there is
 * nothing" once and answered correctly once — and "there is nothing" is the answer a person
 * believes without checking.
 *
 * <p>The subject filter is the guess; the sender and the date window are what the person
 * actually said. So the guess is dropped and the rest is run again — no model call, no
 * rewriting, one extra provider call on a question that has already failed.
 *
 * <p>Two rules keep the widened query from being worse than no answer at all:
 *
 * <ul>
 *   <li>A {@code from:} or {@code to:} must survive. Without one, dropping the subject leaves
 *       "everything from the last 30 days", and an answer drawn from that is about somebody
 *       else's mail.</li>
 *   <li>Only a top-level {@code subject:} is dropped. Inside {@code (from:(x) OR
 *       subject:(y))} the filter is doing the opposite job — widening the search — and
 *       removing it would narrow the query instead.</li>
 * </ul>
 */
final class QueryRelaxer {

    private static final Pattern SENDER = Pattern.compile("(?i)(?<![\\w-])(from|to):");

    private QueryRelaxer() {
    }

    /**
     * @return the same query without its subject filter, or {@code null} when there is no
     *         safe way to widen it — which is most queries
     */
    static String widen(String query) {
        if (query == null || query.isBlank() || !query.toLowerCase(Locale.ROOT).contains("subject:")) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String token : topLevelTokens(query)) {
            if (token.toLowerCase(Locale.ROOT).startsWith("subject:")) {
                dropped = true;
                continue;
            }
            kept.add(token);
        }
        if (!dropped || kept.isEmpty()) {
            return null;
        }
        String widened = String.join(" ", kept).trim();
        if (widened.equals(query.trim()) || !SENDER.matcher(widened).find()) {
            return null;
        }
        return widened;
    }

    /** Splits on spaces that are not inside brackets or quotes — {@code subject:(a OR b)} is one. */
    private static List<String> topLevelTokens(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '{')) {
                depth++;
            } else if (!quoted && (c == ')' || c == '}')) {
                depth = Math.max(0, depth - 1);
            }
            if (c == ' ' && depth == 0 && !quoted) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            token.append(c);
        }
        if (token.length() > 0) {
            out.add(token.toString());
        }
        return out;
    }
}
