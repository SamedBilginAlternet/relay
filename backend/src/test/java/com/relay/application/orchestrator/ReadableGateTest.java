package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Whatever a person is asked to approve has to be the thing that will be sent.
 *
 * <p>Live, someone was shown this and asked to press Onayla:
 *
 * <pre>KAN projesinde {{steps[0].result.issues.length}} adet açık kayıt vardır.</pre>
 *
 * <p>The message never reached Slack — the gate in front of the provider caught it — but that
 * is the wrong place to catch it. The person had already spent their attention deciding about
 * a sentence that was never going out, and pressing Onayla bought a model round and a return
 * to the same screen. The address check was moved in front of the approval for precisely this
 * reason; the placeholder and filler checks had been left behind it.
 */
class ReadableGateTest {

    private final TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
    private final TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();

    private RunService service(LlmClient llm, ToolRegistry tools) {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm), new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
    }

    private static ToolRegistry slackTools() {
        FixtureStore fixtures = new FixtureStore();
        return new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
    }

    /** A specialist that keeps handing back the template it was given. */
    private static LlmClient stubborn(ToolRegistry tools, String text) {
        return new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                if (request.purpose() == com.relay.application.port.LlmPurpose.TOOL_PARAMS) {
                    return new LlmResponse("{\"channel\":\"#all-samed\",\"text\":\"" + text + "\"}",
                            10, 10, 0, "stubborn-stub", true);
                }
                return delegate.complete(request);
            }

            @Override
            public String name() {
                return "stubborn-stub";
            }

            @Override
            public boolean degraded() {
                return true;
            }
        };
    }

    @Test
    void a_step_with_an_unresolved_placeholder_never_reaches_the_approval_gate() {
        ToolRegistry tools = slackTools();
        RunService service = service(stubborn(tools, "KAN projesinde {{steps[0].length}} kayıt var."), tools);

        Run run = service.startFromPlaybook("Kayıtları say ve ekibe yaz", "test",
                List.of(new RunService.SeedStep("Ekibe yaz", "slack.postMessage",
                        Map.of("channel", "#all-samed", "text", "{{steps[0].length}}"))),
                1.0);

        assertThat(run.steps())
                .as("nothing carrying a template marker is ever put in front of a person")
                .noneSatisfy(step -> {
                    assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
                });
        assertThat(events.ofType(com.relay.application.port.RunEvent.STEP_AWAITING)).isEmpty();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).error()).contains("çözülmemiş yer tutucu");
    }

    /** The specialist usually gets it right on the second try, and it is given one. */
    @Test
    void a_step_that_rewrites_itself_reaches_the_gate_with_something_readable() {
        ToolRegistry tools = slackTools();
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient improving = new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                if (request.purpose() == com.relay.application.port.LlmPurpose.TOOL_PARAMS) {
                    String text = turns.getAndIncrement() == 0
                            ? "KAN projesinde {{steps[0].length}} kayıt var."
                            : "KAN projesinde 3 açık kayıt var: KAN-10, KAN-11, KAN-12.";
                    return new LlmResponse("{\"channel\":\"#all-samed\",\"text\":\"" + text + "\"}",
                            10, 10, 0, "improving-stub", true);
                }
                return delegate.complete(request);
            }

            @Override
            public String name() {
                return "improving-stub";
            }

            @Override
            public boolean degraded() {
                return true;
            }
        };
        RunService service = service(improving, tools);

        Run run = service.startFromPlaybook("Kayıtları say ve ekibe yaz", "test",
                List.of(new RunService.SeedStep("Ekibe yaz", "slack.postMessage",
                        Map.of("channel", "#all-samed", "text", "{{steps[0].length}}"))),
                1.0);

        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the rewritten step should be at the gate"));
        assertThat(String.valueOf(gate.params().get("text")))
                .doesNotContain("{{")
                .contains("KAN-10");
    }

    /** And a person who types one into the box is told at once, next to the box. */
    @Test
    void a_placeholder_typed_at_the_gate_is_refused_with_the_field_that_carries_it() {
        ToolRegistry tools = slackTools();
        RunService service = service(new StubLlmClient(tools), tools);

        Run run = service.startFromPlaybook("Ekibe yaz", "test",
                List.of(new RunService.SeedStep("Ekibe yaz", "slack.postMessage",
                        Map.of("channel", "#all-samed", "text", "KAN-10 kapandı."))),
                1.0);
        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> service.approve(run.id(), gate.id(),
                Map.of("text", "{{steps[0].summary}}"), "qa@relay.test"))
                .isInstanceOf(RunService.InvalidParams.class)
                .satisfies(e -> assertThat(((RunService.InvalidParams) e).fields()).containsKey("text"));

        assertThat(run.steps().get(0).status())
                .as("a refused edit changes nothing and the step stays at the gate")
                .isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(String.valueOf(run.steps().get(0).params().get("text"))).isEqualTo("KAN-10 kapandı.");
        assertThat(run.messages())
                .as("nothing that did not happen is written down as approved")
                .noneSatisfy(m -> assertThat(m.content()).startsWith("Onaylandı"));
    }
}
