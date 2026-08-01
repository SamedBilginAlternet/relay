package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Connection;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A container the user configured is used when the model leaves it out, not only when the
 * model gets it wrong.
 *
 * <p>Live, on the playbook "Maili işe çevir" with {@code projectKey = KAN} sitting on the
 * Jira connection:
 *
 * <pre>Jira Uzmanı→ Parametreler şemaya uymadı: $.projectKey is required; $.summary is required</pre>
 *
 * <p>{@code groundContainers} is written to replace <em>a container the model invented</em>
 * with the configured one, and {@code CONTAINER_DEFAULTS} already maps {@code projectKey} to
 * the connection. But it iterates the fields that are present and skips blank ones, and it
 * runs after the schema check — so an absent container was the one case nothing covered, and
 * the run failed on a value the user had already set.
 *
 * <p>The boundary this test also pins: {@code summary} is content. Nothing fills it, and a
 * step that has no summary still fails.
 */
class MissingContainerComesFromTheConnectionTest {

    private final TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
    private final TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
    private final TestDoubles.InMemoryConnectionRepository connections =
            new TestDoubles.InMemoryConnectionRepository();

    private RunService service(LlmClient llm, ToolRegistry tools) {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, connections, journal, clock),
                new Verifier(llm), new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
    }

    private static ToolRegistry jiraTools() {
        return new ToolRegistryImpl(List.of(new JiraTool.CreateIssue("replay", new FixtureStore())));
    }

    /** The Jira connection as the user set it up: the project key is configured. */
    private void jiraConnected() {
        connections.save(Connection.of("jira", Map.of(
                "baseUrl", "https://alternet.atlassian.net",
                "email", "samed.bilgin@alternet.com.tr",
                "apiToken", "secret",
                "projectKey", "KAN"), Instant.parse("2026-08-01T13:18:00Z")));
    }

    /** A specialist that writes the summary and says nothing about where the issue goes. */
    private static LlmClient writesOnly(ToolRegistry tools, String content) {
        return new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                    return new LlmResponse(content, 10, 10, 0, "writes-only-stub", true);
                }
                return delegate.complete(request);
            }

            @Override
            public String name() {
                return "writes-only-stub";
            }

            @Override
            public boolean degraded() {
                return true;
            }
        };
    }

    @Test
    void a_project_key_the_model_never_wrote_is_taken_from_the_connection() {
        jiraConnected();
        ToolRegistry tools = jiraTools();
        RunService service = service(
                writesOnly(tools, "{\"summary\":\"Fatura akışı hata veriyor\"}"), tools);

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the step should be at the gate, not failed"));
        assertThat(gate.params())
                .as("the project the user configured, not one the model guessed")
                .containsEntry("projectKey", "KAN");
        assertThat(gate.params()).containsEntry("summary", "Fatura akışı hata veriyor");
    }

    @Test
    void a_missing_summary_is_never_filled_in_for_the_model() {
        jiraConnected();
        ToolRegistry tools = jiraTools();
        RunService service = service(writesOnly(tools, "{\"description\":\"Mailden geliyor.\"}"), tools);

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        assertThat(run.status())
                .as("content nobody wrote is a failure, not a gap to paper over")
                .isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).error()).contains("summary");
        assertThat(run.steps().get(0).params())
                .as("nothing borrowed the goal text to stand in for the title")
                .doesNotContainEntry("summary", "Maili işe çevir");
    }

    @Test
    void without_a_configured_project_the_step_fails_instead_of_guessing_one() {
        ToolRegistry tools = jiraTools();
        RunService service = service(
                writesOnly(tools, "{\"summary\":\"Fatura akışı hata veriyor\"}"), tools);

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps().get(0).error()).contains("projectKey");
    }
}
