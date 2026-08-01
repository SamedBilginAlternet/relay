package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.RunEvent;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A step whose precondition came back empty is SKIPPED — not failed, and not faked.
 *
 * <p>Live, 2026-08-01 17:36, on the goal "Bugünkü maillerime bak; iş talebi ya da hata
 * bildirimi olanlar için Jira kaydı aç ve kaydı açtığımı ilgili kanaldan bildir":
 *
 * <pre>
 * Doğrulayıcı → Koordinatör: Adım 1 doğrulandı: Bugünün mailleri başarıyla listelendi.
 *   İçeriklerinde iş talebi veya hata bildirimi içeren bir mail bulunmadığı tespit edildi.
 * Koordinatör → Jira Uzmanı: Parametreler onaya sunulabilir değil (1/2): jira.createIssue
 *   için parametreler eksik: $.summary is required. Bu hâliyle sağlayıcıya gönderilemez…
 * </pre>
 *
 * <p>Step 1 was verified: zero qualifying mails. Step 2 then tried to draft a
 * {@code jira.createIssue} anyway. The model cannot write the summary of a mail that does
 * not exist; the schema guard rightly refused to invent one (#155), and after the retries
 * the run closed FAILED. Every move was correct except the last: the honest outcome is
 * "koşulu sağlayan mail yoktu, kayıt açılmadı" — a DONE run with the writes skipped.
 *
 * <p>The skip is grounded, not inferred: nothing regexes the verifier's Turkish. The
 * specialist is offered an explicit escape hatch ({@code {"skip": true, "reason": …}}),
 * only on steps whose parameters derive from earlier results, and the reason has to name
 * what was looked for. A skip that arrives without that earns the invalid-draft path —
 * #155 stands, and skipping never becomes the model's way out of writing content.
 */
class EmptyPreconditionSkipsTheStepTest {

    /** The transcript's goal, verbatim. */
    private static final String GOAL = "Bugünkü maillerime bak; iş talebi ya da hata bildirimi"
            + " olanlar için Jira kaydı aç ve kaydı açtığımı ilgili kanaldan bildir.";

    private static final String NO_MAIL =
            "Bugünkü maillerde iş talebi ya da hata bildirimi içeren bir mail bulunamadı.";
    private static final String NO_RECORD =
            "Önceki adımlarda açılmış bir Jira kaydı olmadığı için duyurulacak bir şey yok.";

    private static ToolRegistry mailToJiraToSlack() {
        FixtureStore fixtures = new FixtureStore();
        return new ToolRegistryImpl(List.of(
                new GmailTool.ListToday("replay", fixtures, null),
                new JiraTool.CreateIssue("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
    }

    private static List<RunService.SeedStep> plan() {
        return List.of(
                new RunService.SeedStep("Bugünün maillerini listele", "gmail.listToday", Map.of()),
                new RunService.SeedStep("İş talebi olan mailler için Jira kaydı aç",
                        "jira.createIssue", Map.of()),
                new RunService.SeedStep("Açılan kaydı kanaldan bildir", "slack.postMessage", Map.of()));
    }

    /**
     * A specialist that answers the parameter question per tool and records every request —
     * everything else falls through to the deterministic stub.
     */
    private static final class ScriptedSpecialist implements LlmClient {
        final List<LlmRequest> paramRequests = new ArrayList<>();
        private final StubLlmClient delegate;
        private final Map<String, String> paramAnswerByTool;

        ScriptedSpecialist(ToolRegistry tools, Map<String, String> paramAnswerByTool) {
            this.delegate = new StubLlmClient(tools);
            this.paramAnswerByTool = paramAnswerByTool;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                paramRequests.add(request);
                String tool = request.context() instanceof Map<?, ?> ctx
                        ? String.valueOf(ctx.get("tool")) : "";
                String answer = paramAnswerByTool.get(tool);
                if (answer != null) {
                    return new LlmResponse(answer, 10, 10, 0, "scripted-specialist", true);
                }
            }
            return delegate.complete(request);
        }

        @Override
        public String name() {
            return "scripted-specialist";
        }

        @Override
        public boolean degraded() {
            return true;
        }
    }

    private static String skip(String reason) {
        return "{\"skip\": true, \"reason\": \"" + reason + "\"}";
    }

    // ---- the whole transcript, replayed against the fix --------------------

    @Test
    void a_run_whose_condition_finds_nothing_skips_the_writes_and_closes_done() {
        ToolRegistry tools = mailToJiraToSlack();
        ScriptedSpecialist llm = new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip(NO_MAIL),
                "slack.postMessage", skip(NO_RECORD)));
        OrchestratorHarness h = OrchestratorHarness.of(tools, llm);

        Run run = h.service.startFromPlaybook(GOAL, "test", plan(), 1.0);

        assertThat(run.status())
                .as("koşulu sağlayan mail yoktu — that is the flow working, not failing")
                .isEqualTo(RunStatus.DONE);
        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.DONE);
        assertThat(run.steps().get(1).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(run.steps().get(1).skipReason()).isEqualTo(NO_MAIL);
        assertThat(run.steps().get(2).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(run.steps().get(2).skipReason()).isEqualTo(NO_RECORD);
    }

    @Test
    void a_skipped_write_never_parks_in_front_of_a_person() {
        ToolRegistry tools = mailToJiraToSlack();
        OrchestratorHarness h = OrchestratorHarness.of(tools, new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip(NO_MAIL),
                "slack.postMessage", skip(NO_RECORD))));

        h.service.startFromPlaybook(GOAL, "test", plan(), 1.0);

        assertThat(h.events.ofType(RunEvent.STEP_AWAITING))
                .as("there is nothing to approve — no draft exists and none was asked about")
                .isEmpty();
    }

    @Test
    void the_skip_is_said_out_loud_to_the_user_with_its_reason() {
        ToolRegistry tools = mailToJiraToSlack();
        OrchestratorHarness h = OrchestratorHarness.of(tools, new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip(NO_MAIL),
                "slack.postMessage", skip(NO_RECORD))));

        Run run = h.service.startFromPlaybook(GOAL, "test", plan(), 1.0);

        assertThat(run.messages())
                .as("a silent skip would make a wrong skip invisible — the journal is the alarm")
                .anySatisfy(message -> {
                    assertThat(message.toAgent()).isEqualTo(AgentRole.USER);
                    assertThat(message.content()).contains("Adım 2 atlandı: " + NO_MAIL);
                });
    }

    /**
     * The dependent step resolves through the same mechanism, not through a cascade rule:
     * the Slack specialist reads the skip record in its PREVIOUS RESULTS — no opened issue
     * anywhere — and skips with its own reason. This asserts the evidence really is in the
     * prompt, which is the part a fake model cannot fake.
     */
    @Test
    void the_dependent_step_sees_the_skip_in_its_previous_results() {
        ToolRegistry tools = mailToJiraToSlack();
        ScriptedSpecialist llm = new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip(NO_MAIL),
                "slack.postMessage", skip(NO_RECORD)));
        OrchestratorHarness h = OrchestratorHarness.of(tools, llm);

        h.service.startFromPlaybook(GOAL, "test", plan(), 1.0);

        LlmRequest slackTurn = llm.paramRequests.stream()
                .filter(r -> r.context() instanceof Map<?, ?> ctx
                        && "slack.postMessage".equals(ctx.get("tool")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the slack step should have had its turn"));
        assertThat(slackTurn.user())
                .contains("PREVIOUS RESULTS")
                .as("the jira step's skip record is the evidence the slack step decides on")
                .contains(NO_MAIL);
        assertThat(slackTurn.system())
                .as("the escape hatch is offered where prior results exist")
                .contains("\"skip\": true");
    }

    // ---- the guard rails the skip must not loosen ---------------------------

    /**
     * #155's boundary: the escape hatch is only offered — and only honoured — where an
     * emptiness could have been observed. A first step has no earlier results; its
     * parameters come from the goal, and a goal is a request, not evidence of absence.
     */
    @Test
    void a_skip_on_a_step_with_no_prior_results_is_refused_as_an_invalid_draft() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.CreateIssue("replay", fixtures)));
        OrchestratorHarness h = OrchestratorHarness.of(tools, new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip(NO_MAIL))));

        Run run = h.service.startFromPlaybook("Sunucu hatası için Jira kaydı aç", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())), 1.0);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(run.steps().get(0).error())
                .as("the refusal names the rule, so the next turn knows what was wrong")
                .contains("önceki adımların sonucuna dayanan");
    }

    /** And where it is not offered, the prompt does not whisper it either. */
    @Test
    void the_escape_hatch_is_not_offered_to_a_step_that_derives_from_the_goal() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.CreateIssue("replay", fixtures)));
        ScriptedSpecialist llm = new ScriptedSpecialist(tools, Map.of());
        OrchestratorHarness h = OrchestratorHarness.of(tools, llm);

        h.service.startFromPlaybook("KAN projesine kayıt aç: ödeme servisi 502 dönüyor", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())), 1.0);

        assertThat(llm.paramRequests).isNotEmpty();
        assertThat(llm.paramRequests.get(0).system()).doesNotContain("skip");
    }

    /**
     * The reason is the whole defence against the lazy skip, so its shape is asserted, not
     * its presence: "yok" names nothing and is treated exactly like a draft that failed the
     * schema — told what was wrong, retried within the same bound, failed at the bound.
     */
    @Test
    void a_skip_whose_reason_names_nothing_is_refused() {
        ToolRegistry tools = mailToJiraToSlack();
        OrchestratorHarness h = OrchestratorHarness.of(tools, new ScriptedSpecialist(tools, Map.of(
                "jira.createIssue", skip("yok"),
                "slack.postMessage", skip("yok"))));

        Run run = h.service.startFromPlaybook(GOAL, "test", plan(), 1.0);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        Step jira = run.steps().get(1);
        assertThat(jira.status()).isEqualTo(StepStatus.FAILED);
        assertThat(jira.error()).contains("skip gerekçesi");
    }

    @Test
    void the_reason_shape_wants_a_sentence_not_a_shrug() {
        assertThat(ToolAgent.presentableSkipReason(NO_MAIL)).isTrue();
        assertThat(ToolAgent.presentableSkipReason(null)).isFalse();
        assertThat(ToolAgent.presentableSkipReason("  ")).isFalse();
        assertThat(ToolAgent.presentableSkipReason("yok")).isFalse();
        assertThat(ToolAgent.presentableSkipReason("bulunamadı")).isFalse();
        assertThat(ToolAgent.presentableSkipReason("{{steps[0].reason}} bulunamadığı için atlandı"))
                .as("a template marker is not a reason — nobody is going to substitute it")
                .isFalse();
    }

    // ---- the closing line ---------------------------------------------------

    /**
     * The Summarizer's grounding rule (#147) forbids the summary claiming a record was
     * opened; this asserts the other half — the skip reason reaches it as evidence, so the
     * closing line can say WHY nothing was written and survive the invention guard.
     */
    @Test
    void the_closing_summary_is_handed_the_skip_reason_as_evidence() {
        List<LlmRequest> seen = new ArrayList<>();
        LlmClient quoting = new LlmClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                seen.add(request);
                return new LlmResponse("Bugünkü maillerde iş talebi ya da hata bildirimi"
                        + " bulunmadığı için Jira kaydı açılmadı ve kanala bildirim yapılmadı.",
                        200, 60, 0.0001, "test:model", false);
            }

            @Override
            public String name() {
                return "quoting";
            }

            @Override
            public boolean degraded() {
                return false;
            }
        };

        Run run = Run.create(GOAL, java.time.Instant.parse("2026-08-01T17:36:00Z"), 1.0);
        Step read = Step.create(run.id(), 1, "Bugünün maillerini listele",
                AgentRole.toolAgent("gmail.listToday"), "gmail.listToday", Map.of());
        read.markDone(Map.of("messages", List.of()), java.time.Instant.now());
        Step write = Step.create(run.id(), 2, "İş talebi olan mailler için Jira kaydı aç",
                AgentRole.toolAgent("jira.createIssue"), "jira.createIssue", Map.of());
        write.markSkipped(NO_MAIL, java.time.Instant.now());
        run.replaceSteps(List.of(read, write));

        Summarizer.Outcome outcome = new Summarizer(quoting).summarise(run, false);

        assertThat(seen.get(0).user())
                .as("the reason is in ADIMLAR, so the model can quote it instead of inventing")
                .contains("atlandı: " + NO_MAIL);
        assertThat(outcome)
                .as("a summary that only explains why nothing was written is grounded")
                .isNotNull();
    }
}
