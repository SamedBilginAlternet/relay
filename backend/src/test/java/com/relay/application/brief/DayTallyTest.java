package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The written summary is the first thing to disappear when the model budget runs out, and
 * live that has been most of the time — so the screen kept showing evidence with no
 * conclusion. These numbers are arithmetic over what the providers already returned: they
 * cannot be wrong, and they are there whether or not a model answered.
 */
class DayTallyTest {

    private static BriefItem mail(String id, boolean bulk) {
        return new BriefItem("gmail:" + id, "gmail", "mail", "", "Konu " + id, "Gönderen", "1sa önce",
                "Gönderen", "https://mail", "2026-08-01T06:00:00Z", BriefItem.DEFAULT,
                Map.of("bulk", bulk));
    }

    private static BriefItem item(String id, String source, String kind, String meta) {
        return new BriefItem(source + ":" + id, source, kind, id, "Başlık " + id, "alt", meta,
                "kim", "https://x", "2026-08-01T06:00:00Z", BriefItem.DEFAULT, Map.of());
    }

    @Test
    void a_mailing_list_is_not_a_person_writing_to_you() {
        DayTally tally = DayTally.of(
                List.of(mail("1", false), mail("2", true), mail("3", true)),
                List.of(), List.of(), List.of(), 0);

        assertThat(tally.counts()).containsEntry("inbox", 3)
                .containsEntry("inboxPersonal", 1).containsEntry("inboxBulk", 2);
        assertThat(tally.lines()).first().asString().contains("1 mail bir kişiden", "2 bülten");
        assertThat(tally.headline()).contains("1 iş");
    }

    @Test
    void the_headline_counts_work_and_names_the_urgent_ones() {
        DayTally tally = DayTally.of(
                List.of(mail("1", false)),
                List.of(item("KAN-4", "jira", "issue", "25dk önce")),
                List.of(item("acme/pay#12", "github", "pr", "1sa önce")),
                List.of(item("evt", "calendar", "event", "14:00")),
                2);

        assertThat(tally.headline()).isEqualTo("Bugün 3 iş seni bekliyor, 2 tanesi acil · 1 toplantı.");
        assertThat(tally.lines()).anySatisfy(line -> assertThat(line).contains("ilki 14:00"));
    }

    /** An empty day says so plainly instead of dressing up zeros. */
    @Test
    void an_empty_day_is_not_reported_as_a_busy_one() {
        DayTally tally = DayTally.of(List.of(), List.of(), List.of(), List.of(), 0);

        assertThat(tally.headline()).isEqualTo("Bugün seni bekleyen bir şey görünmüyor.");
        assertThat(tally.lines()).isEmpty();
    }

    @Test
    void a_day_of_only_meetings_says_that() {
        DayTally tally = DayTally.of(List.of(), List.of(), List.of(),
                List.of(item("a", "calendar", "event", "09:30")), 0);

        assertThat(tally.headline()).isEqualTo("Bugün tek işin bir toplantı.");
    }

    /** Only mailings arrived: honest, and not counted as work waiting on the user. */
    @Test
    void an_inbox_of_only_newsletters_is_not_work() {
        DayTally tally = DayTally.of(List.of(mail("1", true), mail("2", true)),
                List.of(), List.of(), List.of(), 0);

        assertThat(tally.headline()).isEqualTo("Bugün seni bekleyen bir şey görünmüyor.");
        assertThat(tally.lines()).first().asString().contains("hepsi bülten");
    }
}
