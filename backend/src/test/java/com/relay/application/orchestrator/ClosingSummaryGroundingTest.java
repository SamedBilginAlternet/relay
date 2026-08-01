package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Read off the live transcript on 2026-08-01. The goal was the day's brief: a payment
 * e-mail thread, GitHub issue #42, pull request #43, and one calendar event. The planner
 * produced ONE step, {@code gmail.search}, and it ran. Then the run closed with:
 *
 * <blockquote>"…R-44W-VG2 numaralı sipariş için kritik ödeme hatası incelendi.
 * issue-to-notion-demo kanalında #42 numaralı login yönlendirme hatası ve #43 numaralı
 * README kurulum PR'ı <b>üzerinde çalışıldı</b>. Gün içerisinde toplam 1 adet takvim
 * etkinliği olan 'Hackathon takvimi incele' <b>tamamlandı</b>."</blockquote>
 *
 * <p>GitHub was never called. The calendar was never read. Three completion claims, all of
 * them read straight off the goal, printed as the last line of an audit trail that shows a
 * single mail search — the trail contradicting itself in its own closing sentence.
 *
 * <p>Two things let it through. The identifier guard only knew {@code KAN-42}-shaped keys,
 * so {@code #42} was never examined. And its evidence was {@code goal + steps}, which means
 * asking for something counted as having done it.
 *
 * <p>The product's entire claim is that you can see what it did. A closing line that says
 * work happened when it did not is that claim inverted, and it is the sentence a reader
 * trusts most because it is the one written for them.
 */
class ClosingSummaryGroundingTest {

    /** Returns whatever closing text the test wants to put in front of the guard. */
    private static class Says implements LlmClient {
        private final String text;

        Says(String text) {
            this.text = text;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(text, 400, 90, 0.000_2, "test:model", false);
        }

        @Override
        public String name() {
            return "says";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private static Run runWith(String goal, Object result) {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        Run run = Run.create(goal, clock.now(), 1.0);
        Step step = Step.create(run.id(), 1, "Ödeme hatasına ilişkin e-posta zincirini ara",
                AgentRole.toolAgent("gmail.search"), "gmail.search",
                Map.of("query", "Ödeme adımında hata alıyoruz"));
        step.markRunning(clock.now());
        step.markDone(result, clock.now());
        run.replaceSteps(List.of(step));
        return run;
    }

    /** The whole live goal, so the guard is tested against the text that fooled it. */
    private static final String GOAL = """
            Bugün öncelikli olarak "Ödeme adımında hata alıyoruz — sipariş tamamlanmıyor" \
            e-posta zincirinde bildirilen kritik ödeme hatasına odaklanmanız gerekiyor. \
            Ayrıca issue-to-notion-demo deposundaki #42 "Login sonrası yönlendirme kayboluyor" \
            hatası ile #43 numaralı README kurulum notu PR'ı ilgilenmenizi bekliyor. \
            Günün tek takvim kaydı ise "Hackathon takvimi incele" etkinliğidir.""";

    /** What the one step that ran actually returned: mail, and nothing else. */
    private static final Object MAIL_ONLY = Map.of("messages", List.of(
            Map.of("id", "19fbbf392133acdc",
                    "subject", "Re: Ödeme adımında hata alıyoruz — sipariş tamamlanmıyor",
                    "from", "Samed Bilgin",
                    "snippet", "R-44W-VG2 numaralı sipariş için işlem tamamlanamadı")));

    @Test
    void a_summary_that_reports_work_on_records_no_step_touched_is_dropped() {
        String lied = "\"Ödeme adımında hata alıyoruz\" konulu e-posta zincirinde R-44W-VG2 "
                + "numaralı sipariş için kritik ödeme hatası incelendi. issue-to-notion-demo "
                + "kanalında #42 numaralı login yönlendirme hatası ve #43 numaralı README "
                + "kurulum PR'ı üzerinde çalışıldı.";

        Summarizer.Outcome outcome =
                new Summarizer(new Says(lied)).summarise(runWith(GOAL, MAIL_ONLY), false);

        assertThat(outcome)
                .as("the goal named #42 and #43; the run never went near them")
                .isNull();
    }

    /**
     * The specific regression: the goal used to be evidence, so anything asked for counted
     * as grounded. It is the difference between "you wanted this" and "this happened".
     */
    @Test
    void a_record_key_that_only_ever_appeared_in_the_goal_is_not_grounded() {
        Run run = runWith("KAN-42 kaydını incele ve özetle", MAIL_ONLY);

        Summarizer.Outcome outcome =
                new Summarizer(new Says("KAN-42 kaydı incelendi ve özetlendi.")).summarise(run, false);

        assertThat(outcome).isNull();
    }

    /** And the honest case still gets through, in both provider spellings of a number. */
    @Test
    void a_summary_naming_what_a_step_actually_returned_survives() {
        Object issues = Map.of("issues", List.of(Map.of(
                "repo", "SamedBilginAlternet/issue-to-notion-demo",
                "number", 42,
                "title", "Login sonrası yönlendirme kayboluyor",
                "url", "https://github.com/SamedBilginAlternet/issue-to-notion-demo/issues/42")));

        Summarizer.Outcome outcome = new Summarizer(
                new Says("#42 numaralı login yönlendirme hatası listelendi."))
                .summarise(runWith(GOAL, issues), false);

        assertThat(outcome).isNotNull();
        assertThat(outcome.text()).contains("#42");
    }

    @Test
    void a_summary_with_no_identifiers_at_all_is_left_alone() {
        Summarizer.Outcome outcome = new Summarizer(
                new Says("Ödeme hatasını bildiren e-posta zinciri bulundu; başka bir adım çalışmadı."))
                .summarise(runWith(GOAL, MAIL_ONLY), false);

        assertThat(outcome).isNotNull();
    }
}
