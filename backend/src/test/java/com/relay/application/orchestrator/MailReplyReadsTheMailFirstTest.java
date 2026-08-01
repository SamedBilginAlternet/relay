package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Why a reply is two steps and not one.
 *
 * <p>On the deployed instance a user pressed "Cevap yaz" and Relay created a real Gmail draft
 * titled <em>Re: Cevap</em>. Everything was correct except the part that mattered: the draft
 * hung under the right conversation, and answered a mail nobody had read. The suggestion
 * carried the button's label and a courtesy template, so there was nothing to write from.
 *
 * <p>Reading the message first is what makes the answer an answer. These tests hold that the
 * read happens, that the reply is written from what it returned, and that the extra call is
 * spent only where it buys something — a Jira comment still starts and finishes in one step.
 */
class MailReplyReadsTheMailFirstTest {

    private static final String MESSAGE_ID = "18f2c9a10b3d4e01";

    private RunService runService;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new GmailTool.GetMessage("replay", fixtures, null),
                new GmailTool.CreateDraft("replay", fixtures, null),
                new JiraTool.AddComment("replay", fixtures)));

        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();

        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        PolicyEngine policyEngine = new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm), policyEngine, costMeter, events, journal, clock);
        runService = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
    }

    @Test
    void the_draft_is_written_from_the_mail_and_not_from_the_button() {
        Run run = runService.startFromSuggestion("gmail.createDraft", suggestedParams(),
                "Taslak cevap yaz", mailCard(), null);

        assertThat(run.steps()).hasSize(2);
        Step read = run.steps().get(0);
        Step draft = run.steps().get(1);
        assertThat(read.toolName()).isEqualTo("gmail.getMessage");
        assertThat(read.params()).containsEntry("messageId", MESSAGE_ID);
        assertThat(draft.toolName()).isEqualTo("gmail.createDraft");

        // The read ran on its own — it is a READ, so no gate. The write is where we stop.
        assertThat(read.status()).isEqualTo(StepStatus.DONE);
        assertThat(draft.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        // What the human is shown is the mail's own subject, not the label that started this.
        assertThat(String.valueOf(draft.params().get("subject")))
                .isEqualTo("Re: Ödeme servisi staging'de patlıyor");
        // …and the answer is written from what step one returned, not from the template the
        // card arrived with.
        assertThat(String.valueOf(draft.params().get("body")))
                .contains("Ödeme servisi staging'de patlıyor")
                .doesNotContain("bugün içinde dönüş yapacağım");
        // The conversation is still the one the card pointed at.
        assertThat(draft.params()).containsEntry("threadId", MESSAGE_ID);

        runService.approve(run.id(), draft.id());

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(String.valueOf(draft.result()))
                .contains("subject=Re: Ödeme servisi staging'de patlıyor")
                .contains("status=draft");
    }

    /** The template a suggestion arrives with must not survive as the answer. */
    @Test
    void the_pre_written_reply_never_reaches_the_second_step_as_it_was() {
        Run run = runService.startFromSuggestion("gmail.createDraft", suggestedParams(),
                "Taslak cevap yaz", mailCard(), null);

        Step draft = run.steps().get(1);
        assertThat(String.valueOf(draft.params().get("subject"))).isNotEqualTo("Re: Cevap");
        assertThat(String.valueOf(draft.params().get("body")))
                .isNotEqualTo("Merhaba Ayşe Demir,\n\nMailini aldım, bugün içinde dönüş yapacağım.");
        // The recipient is not content — it came off the card and is left alone.
        assertThat(String.valueOf(draft.params().get("to"))).contains("ayse@alterteam.dev");
    }

    /**
     * The second call is bought, not free. A comment saying "starting on this" needs the
     * record's name, which the goal already carries — reading the whole issue first would
     * spend a provider call and a model turn to learn nothing new.
     */
    @Test
    void a_record_comment_still_starts_and_finishes_in_one_step() {
        Run run = runService.startFromSuggestion("jira.addComment",
                Map.of("issueKey", "KAN-42", "body", "Bugün başlıyorum."),
                "İlerlemeyi kayda yaz",
                new RunService.SuggestionContext("jira:KAN-42", "jira", "Ödeme retry politikası",
                        "Ayşe Demir", "İki gündür Blocked.", null),
                null);

        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).toolName()).isEqualTo("jira.addComment");
    }

    /** No card behind the press means no message to read — and no invented one either. */
    @Test
    void a_draft_with_nothing_to_answer_stays_a_single_step() {
        Run run = runService.startFromSuggestion("gmail.createDraft",
                Map.of("to", "ayse@alterteam.dev", "subject", "Toplantı notları", "body", "Ekte."),
                "Yeni mail yaz", null, null);

        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).toolName()).isEqualTo("gmail.createDraft");
    }

    // ---- the card the user pressed ----------------------------------------

    /** What the brief hands the screen for a mail waiting on an answer. */
    private static RunService.SuggestionContext mailCard() {
        return new RunService.SuggestionContext("gmail:" + MESSAGE_ID, "gmail",
                "Ödeme servisi staging'de patlıyor", "Ayşe Yıldız",
                "Ayşe senden dönüş bekliyor.",
                "https://mail.google.com/mail/u/0/#inbox/" + MESSAGE_ID);
    }

    /** The courtesy draft the suggestion layer seeds — subject and body written blind. */
    private static Map<String, Object> suggestedParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("to", "Ayşe Yıldız <ayse@alterteam.dev>");
        params.put("subject", "Re: Cevap");
        params.put("body", "Merhaba Ayşe Demir,\n\nMailini aldım, bugün içinde dönüş yapacağım.");
        params.put("threadId", MESSAGE_ID);
        return params;
    }
}
