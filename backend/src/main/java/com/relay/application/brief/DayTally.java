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
public record DayTally(String headline, List<String> lines, Map<String, Integer> counts) {

    public Map<String, Object> view() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("headline", headline);
        map.put("lines", lines);
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

        List<String> lines = new ArrayList<>();
        if (!inbox.isEmpty()) {
            lines.add(personalMail == 0
                    ? inbox.size() + " mail geldi, hepsi bülten ve bildirim"
                    : personalMail + " mail bir kişiden geldi"
                            + (mailings > 0 ? " (" + mailings + " bülten ayrıldı)" : ""));
        }
        if (!work.isEmpty()) {
            lines.add(work.size() + " kayıt üstünde");
        }
        if (!code.isEmpty()) {
            lines.add(code.size() + " PR ve issue sende");
        }
        if (!calendar.isEmpty()) {
            String first = calendar.get(0).meta();
            lines.add(calendar.size() + " toplantı"
                    + (first == null || first.isBlank() ? "" : " — ilki " + first));
        }

        return new DayTally(headline(personalMail, work.size(), code.size(), calendar.size(), urgent),
                lines, counts);
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
