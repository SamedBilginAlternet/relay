package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The auditor is the whole product claim, and it had no test of its own — every run that
 * ever exercised it passed on the first try, through {@code OrchestratorHappyPathTest}.
 *
 * <p>What that hid: "geçti sayma" was one condition covering three different answers.
 * A model that replied {@code {"reason": "mesaj hiçbir bulgu taşımıyor"}} — a plainly
 * negative judgement that simply left out the field the schema declares required — was read
 * as a pass, and the user was shown "doğrulandı" over the auditor's own objection. The
 * unparseable answer still passes, on purpose (docs/NASIL-CALISIYOR.md §10): a verifier that
 * cannot speak must not lock a run. A verifier that spoke and said something else is not the
 * same case, and these tests hold the two apart.
 */
class VerifierTest {

    /** Answers a fixed list of verdicts, repeating the last one for every further call. */
    private static class VerdictLlm implements LlmClient {
        private final List<String> verdicts;
        private int index;

        VerdictLlm(String... verdicts) {
            this.verdicts = List.of(verdicts);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (!LlmPurpose.VERIFY.equals(request.purpose())) {
                return new LlmResponse("{}", 10, 5, 0.0001, "scripted", false);
            }
            String content = verdicts.get(Math.min(index++, verdicts.size() - 1));
            return new LlmResponse(content, 10, 5, 0.0001, "scripted", false);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    /** A READ tool, so the step runs without an approval gate and always succeeds. */
    private static class ListTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "jira.searchIssues";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            return ToolResult.ok(Json.object().put("issues", 0), 4, "replay");
        }
    }

    private record Rig(RunService service, Run run, Step step, ListTool tool) {
    }

    /** One READ step, seeded rather than planned, driven to wherever the verdict takes it. */
    private Rig drive(String... verdicts) {
        ListTool tool = new ListTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new VerdictLlm(verdicts);
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0);

        Run run = Run.create("Açık kayıtları listele", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step step = Step.create(run.id(), 1, "Kayıtları ara", "jira-uzmani",
                "jira.searchIssues", Map.of());
        run.replaceSteps(List.of(step));
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run, step, tool);
    }

    /** Today's deliberate debt, written down so nobody "fixes" it by accident. */
    @Test
    void an_unparseable_verdict_still_lets_the_run_through() {
        Rig rig = drive("Bence sonuç fena değil, devam edebilirsin.");

        assertThat(rig.step().status()).isEqualTo(StepStatus.DONE);
        assertThat(rig.run().status()).isEqualTo(RunStatus.DONE);
        assertThat(rig.tool().calls).isEqualTo(1);
    }

    @Test
    void a_verdict_that_forgets_to_say_pass_is_not_treated_as_a_pass() {
        Rig rig = drive("{\"reason\":\"mesaj hiçbir bulgu taşımıyor\"}",
                "{\"pass\":true,\"reason\":\"tamam\"}");

        // Sent back once, not waved through: the tool ran a second time before the step
        // was allowed to be done.
        assertThat(rig.step().attempts()).isEqualTo(1);
        assertThat(rig.tool().calls).isEqualTo(2);
        assertThat(rig.step().status()).isEqualTo(StepStatus.DONE);
        assertThat(rig.run().messages()).anySatisfy(message -> {
            assertThat(message.content()).contains("Geri gönderildi (1/2)");
            // The auditor's own words survive into the trail.
            assertThat(message.content()).contains("mesaj hiçbir bulgu taşımıyor");
        });
    }

    @Test
    void a_verdict_that_says_false_sends_the_step_back_at_most_twice() {
        Rig rig = drive("{\"pass\":false,\"reason\":\"sonuçta hiçbir kayıt yok\"}");

        assertThat(rig.step().attempts()).isEqualTo(Step.MAX_RETRIES);
        assertThat(rig.tool().calls).isEqualTo(Step.MAX_RETRIES + 1);
        assertThat(rig.step().status()).isEqualTo(StepStatus.FAILED);
        assertThat(rig.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content())
                        .contains("Doğrulama iki denemede de geçmedi: sonuçta hiçbir kayıt yok"));
    }

    /** Models answer JSON-ish, not JSON: the schema says boolean, the wire says "false". */
    @Test
    void a_string_false_is_read_as_a_failing_verdict() {
        Run run = Run.create("Açık kayıtları listele", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step step = Step.create(run.id(), 1, "Kayıtları ara", "jira-uzmani",
                "jira.searchIssues", Map.of());

        Verifier.Verdict verdict = new Verifier(new VerdictLlm("{\"pass\":\"false\",\"reason\":\"boş\"}"))
                .verify(run, step, Map.of("issues", 0));

        assertThat(verdict.pass()).isFalse();
        assertThat(verdict.reason()).isEqualTo("boş");
    }
}
