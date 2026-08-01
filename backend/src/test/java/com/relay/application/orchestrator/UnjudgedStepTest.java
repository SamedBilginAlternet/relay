package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.support.TestDoubles;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Read from the live transcript on 2026-08-01, on a run whose every call went to the
 * paid tier because all seven Groq keys were at their daily wall:
 *
 * <blockquote>{@code verifier → coordinator: Adım 1 doğrulandı: verifier could not parse
 * a verdict, accepting}</blockquote>
 *
 * <p>An English apology printed under a Turkish word meaning the opposite of what had
 * happened. The auditor had said nothing readable, and the product wrote that up as its
 * approval — on a step of a run that had also done nothing.
 *
 * <p>Letting the step through is still right and is not what these tests are about: an
 * auditor that cannot speak must not lock a run that has already done its work
 * (NASIL-CALISIYOR.md §10). What must not happen is spending the word "doğrulandı" on a
 * step nobody checked. On the fallback provider that silence is not the exception — it is
 * every step of every run.
 */
class UnjudgedStepTest {

    private static class Answers implements LlmClient {
        private final String reply;

        Answers(String reply) {
            this.reply = reply;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(reply, 400, 80, 0.000_1, "fallback:reasoner", false);
        }

        @Override
        public String name() {
            return "answers";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private Verifier.Verdict verdictFor(String reply) {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        Run run = Run.create("KAN kayıtlarını listele", clock.now(), 1.0);
        Step step = Step.create(run.id(), 1, "Kayıtları ara",
                AgentRole.toolAgent("jira.searchIssues"), "jira.searchIssues", Map.of());
        return new Verifier(new Answers(reply)).verify(run, step, Map.of("issues", 3));
    }

    @Test
    void an_auditor_that_says_nothing_readable_lets_the_step_through_but_does_not_vouch_for_it() {
        Verifier.Verdict verdict = verdictFor("Let me think about whether this is right…");

        // Unchanged: the run is not locked by an auditor that could not speak.
        assertThat(verdict.pass()).isTrue();
        // New, and the whole point: it was not judged, so nothing may call it verified.
        assertThat(verdict.judged()).isFalse();
        // And the reason reaches a Turkish product in Turkish.
        assertThat(verdict.reason()).isEqualTo("denetçi bir yargı veremedi");
    }

    @Test
    void a_real_verdict_is_judged_and_says_so() {
        Verifier.Verdict pass = verdictFor("{\"pass\": true, \"reason\": \"üç kayıt döndü\"}");

        assertThat(pass.pass()).isTrue();
        assertThat(pass.judged()).isTrue();
        assertThat(pass.reason()).isEqualTo("üç kayıt döndü");
    }

    /**
     * The neighbouring case, already correct and worth holding: JSON that carries a
     * complaint but leaves out the required verdict field is a negative judgement, not a
     * silence, and it fails the step rather than passing it.
     */
    @Test
    void a_complaint_with_no_verdict_field_still_fails_the_step() {
        Verifier.Verdict verdict = verdictFor("{\"reason\": \"sonuç hiçbir bulgu taşımıyor\"}");

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.judged()).isTrue();
        assertThat(verdict.reason()).contains("sonuç hiçbir bulgu taşımıyor");
    }
}
