package com.relay.application.brief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The day in counted facts — no model involved.
 *
 * <p>The written summary ({@link DigestService}) says what the day <em>means</em>, and it is
 * the first thing to disappear when the Groq budget runs out, which live has been most of
 * the time. So the question the screen exists for — "bugün ne var" — cannot depend on it.
 *
 * <p>Everything here is arithmetic over what the providers already returned: how many mails
 * are from a person rather than a mailing list, how many records are assigned, how many pull
 * requests wait, when the first meeting starts. Numbers that cannot be wrong, and that are
 * there whether or not a model answered.
 */
public record DayTally(String headline, List<Line> lines, List<Highlight> highlights,
                      Map<String, Integer> counts) {

    /**
     * One counted arrival, and the provider it was counted from.
     *
     * <p>The text alone used to be the whole line, and the screen wanted to draw Gmail's
     * mark beside "6 mail bir kişiden geldi" and GitHub's beside "3 PR ve issue sende".
     * The only thing the client could have done with a bare string is guess from the
     * Turkish — match "mail", match "PR" — which is an inference the copy invalidates the
     * first time it is reworded, and which no test in either half of the codebase would
     * have caught doing it.
     *
     * <p>It is not a guess here. Every line is emitted inside the {@code if} that already
     * knows which of the four section lists it counted, so the source is read off the
     * branch rather than off the sentence.
     *
     * @param source gmail | jira | github | calendar — the same vocabulary {@link Highlight}
     *               uses, so the UI has one mapping from source to mark and not two
     * @param text   the counted phrase, unchanged
     */
    public record Line(String source, String text) {

        public Map<String, Object> view() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("text", text);
            return map;
        }
    }

    /**
     * One named thing, not a number.
     *
     * <p>"1 mail bir kişiden geldi" tells the reader there is something without telling them
     * what — they still have to go look. The subject and the sender are already on screen
     * three rows down; putting them in the summary costs nothing and answers the question.
     *
     * @param itemId the brief item this points at, so the UI can scroll to it
     * @param source gmail | jira | github | calendar
     * @param label  what it is, in one phrase
     * @param detail who it is from / what state it is in
     */
    public record Highlight(String itemId, String source, String label, String detail) {

        public Map<String, Object> view() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("itemId", itemId);
            map.put("source", source);
            map.put("label", label);
            map.put("detail", detail);
            return map;
        }
    }

    public Map<String, Object> view() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("headline", headline);
        List<Map<String, Object>> counted = new ArrayList<>();
        lines.forEach(line -> counted.add(line.view()));
        map.put("lines", counted);
        List<Map<String, Object>> named = new ArrayList<>();
        highlights.forEach(highlight -> named.add(highlight.view()));
        map.put("highlights", named);
        map.put("counts", counts);
        return map;
    }

    /**
     * @param inbox    today's mail
     * @param work     issues assigned to the user
     * @param code     pull requests and issues from GitHub
     * @param calendar today's events, in start order
     * @param urgent   how many insight cards came back high urgency
     */
    public static DayTally of(List<BriefItem> inbox, List<BriefItem> work, List<BriefItem> code,
                              List<BriefItem> calendar, int urgent) {
        int personalMail = 0;
        for (BriefItem item : inbox) {
            if (!Boolean.TRUE.equals(item.ref().get("bulk"))) {
                personalMail++;
            }
        }
        int mailings = inbox.size() - personalMail;

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("inbox", inbox.size());
        counts.put("inboxPersonal", personalMail);
        counts.put("inboxBulk", mailings);
        counts.put("work", work.size());
        counts.put("code", code.size());
        counts.put("calendar", calendar.size());
        counts.put("urgent", urgent);

        // Each line is built inside the branch that knows which list it counted, and it
        // carries that provider out with it. Reading the source back off the Turkish is
        // the one thing the screen must never have to do.
        List<Line> lines = new ArrayList<>();
        if (!inbox.isEmpty()) {
            lines.add(new Line("gmail", personalMail == 0
                    ? inbox.size() + " mail geldi, hepsi bülten ve bildirim"
                    : personalMail + " mail bir kişiden geldi"
                            + (mailings > 0 ? " (" + mailings + " bülten ayrıldı)" : "")));
        }
        if (!work.isEmpty()) {
            lines.add(new Line("jira", work.size() + " kayıt üstünde"));
        }
        if (!code.isEmpty()) {
            lines.add(new Line("github", code.size() + " PR ve issue sende"));
        }
        if (!calendar.isEmpty()) {
            String first = calendar.get(0).meta();
            lines.add(new Line("calendar", calendar.size() + " toplantı"
                    + (first == null || first.isBlank() ? "" : " — ilki " + first)));
        }

        return new DayTally(headline(personalMail, work.size(), code.size(), calendar.size(), urgent),
                lines, highlights(inbox, work, code, calendar), counts);
    }

    /**
     * The few things worth naming: mail from a person first, because that is the one the
     * reader cannot see from the counts and the one that usually carries a request.
     * Mailings never appear here — they are not work.
     */
    private static List<Highlight> highlights(List<BriefItem> inbox, List<BriefItem> work,
                                              List<BriefItem> code, List<BriefItem> calendar) {
        List<Highlight> out = new ArrayList<>();
        for (BriefItem mail : inbox) {
            if (out.size() >= 2 || Boolean.TRUE.equals(mail.ref().get("bulk"))) {
                continue;
            }
            out.add(new Highlight(mail.id(), "gmail", mail.rowTitle(),
                    join(mail.from(), mail.meta())));
        }
        if (!work.isEmpty()) {
            BriefItem issue = work.get(0);
            out.add(new Highlight(issue.id(), "jira", issue.rowTitle(), issue.subtitle()));
        }
        if (!code.isEmpty()) {
            BriefItem pull = code.get(0);
            out.add(new Highlight(pull.id(), "github", pull.rowTitle(), pull.subtitle()));
        }
        if (!calendar.isEmpty()) {
            BriefItem event = calendar.get(0);
            out.add(new Highlight(event.id(), "calendar", event.rowTitle(), event.meta()));
        }
        return out;
    }

    /** "Ayşe Demir · 2sa önce", skipping whichever half is missing. */
    private static String join(String left, String right) {
        boolean hasLeft = left != null && !left.isBlank();
        boolean hasRight = right != null && !right.isBlank();
        if (hasLeft && hasRight) {
            return left + " · " + right;
        }
        return hasLeft ? left : (hasRight ? right : "");
    }

    /** One sentence that survives an empty day without pretending it was busy. */
    private static String headline(int personalMail, int work, int code, int calendar, int urgent) {
        int waiting = personalMail + work + code;
        if (waiting == 0 && calendar == 0) {
            return "Bugün seni bekleyen bir şey görünmüyor.";
        }
        if (waiting == 0) {
            return calendar == 1 ? "Bugün tek işin bir toplantı." : "Bugün " + calendar + " toplantın var, başka iş yok.";
        }
        StringBuilder sb = new StringBuilder("Bugün ").append(waiting).append(" iş seni bekliyor");
        if (urgent > 0) {
            // "tanesi" rather than a suffix: Turkish vowel harmony would need 2'si, 3'ü,
            // 6'sı… and a wrong suffix is exactly the sort of detail that reads as sloppy.
            sb.append(", ").append(urgent).append(" tanesi acil");
        }
        if (calendar > 0) {
            sb.append(" · ").append(calendar).append(" toplantı");
        }
        return sb.append('.').toString();
    }
}
