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
        assertThat(tally.lines()).first().extracting(DayTally.Line::text).asString()
                .contains("1 mail bir kişiden", "2 bülten");
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
        assertThat(tally.lines()).anySatisfy(line ->
                assertThat(line.text()).contains("ilki 14:00"));
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
        assertThat(tally.lines()).first().extracting(DayTally.Line::text).asString()
                .contains("hepsi bülten");
    }

    /**
     * The screen draws each arrival with its provider's mark beside it. The only other way
     * to know which mark a line wants is to read the Turkish back — match "mail", match
     * "PR" — and that guess breaks silently the first time the copy is reworded or a count
     * reaches a plural form nobody tested. So the source rides on the line, from the branch
     * that counted it.
     */
    @Test
    void every_counted_line_says_which_provider_it_was_counted_from() {
        DayTally tally = DayTally.of(
                List.of(mail("1", false)),
                List.of(item("KAN-4", "jira", "issue", "25dk önce")),
                List.of(item("acme/pay#12", "github", "pr", "1sa önce")),
                List.of(item("evt", "calendar", "event", "14:00")),
                0);

        assertThat(tally.lines()).extracting(DayTally.Line::source)
                .containsExactly("gmail", "jira", "github", "calendar");
    }

    /** A source that appears on no line is a source that contributed nothing today. */
    @Test
    void a_provider_with_nothing_in_it_contributes_no_line_to_put_a_mark_on() {
        DayTally tally = DayTally.of(List.of(), List.of(), List.of(),
                List.of(item("evt", "calendar", "event", "09:30")), 0);

        assertThat(tally.lines()).extracting(DayTally.Line::source).containsExactly("calendar");
    }

    /** The wire shape, because the client rebuilds every field by name. */
    @Test
    void the_payload_carries_the_source_next_to_the_text_it_belongs_to() {
        DayTally tally = DayTally.of(List.of(), List.of(), List.of(),
                List.of(item("evt", "calendar", "event", "09:30")), 0);

        assertThat(tally.view()).extracting("lines").asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
                .singleElement()
                .isEqualTo(Map.of("source", "calendar", "text", "1 toplantı — ilki 09:30"));
    }

    /** A count says there is something; a name says what — and mail is where that matters. */
    @Test
    void the_mail_that_a_person_sent_is_named_not_just_counted() {
        BriefItem personal = new BriefItem("gmail:9", "gmail", "mail", "",
                "Ödeme servisi staging'de patlıyor", "Ayşe Demir", "2sa önce", "Ayşe Demir",
                "https://mail", "2026-08-01T06:00:00Z", BriefItem.DEFAULT, Map.of("bulk", false));

        DayTally tally = DayTally.of(List.of(mail("1", true), personal, mail("2", true)),
                List.of(), List.of(), List.of(), 0);

        assertThat(tally.highlights()).singleElement().satisfies(highlight -> {
            assertThat(highlight.source()).isEqualTo("gmail");
            assertThat(highlight.label()).isEqualTo("Ödeme servisi staging'de patlıyor");
            assertThat(highlight.detail()).isEqualTo("Ayşe Demir · 2sa önce");
            assertThat(highlight.itemId()).isEqualTo("gmail:9");
        });
    }

    @Test
    void a_mailing_is_never_named() {
        DayTally tally = DayTally.of(List.of(mail("1", true), mail("2", true)),
                List.of(), List.of(), List.of(), 0);

        assertThat(tally.highlights()).isEmpty();
    }

    @Test
    void each_source_contributes_the_one_at_its_top() {
        DayTally tally = DayTally.of(List.of(),
                List.of(item("KAN-4", "jira", "issue", "25dk önce")),
                List.of(item("acme/pay#12", "github", "pr", "1sa önce")),
                List.of(item("Sprint planlama", "calendar", "event", "14:00")), 0);

        assertThat(tally.highlights()).extracting(DayTally.Highlight::source)
                .containsExactly("jira", "github", "calendar");
    }
}
