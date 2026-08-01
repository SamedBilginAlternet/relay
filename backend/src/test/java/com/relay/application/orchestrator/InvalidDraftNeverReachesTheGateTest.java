package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.RunEvent;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A person is only ever asked about parameters that could actually be sent.
 *
 * <p>Live, on the playbook "Maili işe çevir", every line stamped 16:18:
 *
 * <pre>
 * Koordinatör   Adım 2 — onay gerekiyor — jira.createIssue için kayıtlı politika: onay ister
 * Sen        →  Onaylandı (samed.bilgin@alternet.com.tr) — devam et.
 * Koordinatör→  Adım 2 sende: Jira kaydını aç
 * Jira Uzmanı→  Parametreler şemaya uymadı: $.projectKey is required; $.summary is required
 * Koordinatör→  Araç hatayı gerekçesiyle döndürdü … tekrar onayına gelecek.
 * Koordinatör   Adım 2 — onay gerekiyor — jira.createIssue için kayıtlı politika: onay ister
 * </pre>
 *
 * <p>The human was asked to approve a write whose required fields were not there, approved
 * it, and the tool refused it for exactly that reason — then the same unsendable draft came
 * back and asked them again. {@code ToolAgent.refreshParams} had already worked out that the
 * draft did not satisfy the tool's schema and handed that verdict to the coordinator, which
 * read the token counts off it and threw the verdict away.
 *
 * <p>The one thing Relay asks of a person is to read what will be sent. A draft that cannot
 * be sent spends that attention on nothing, once per lap, until the retries run out.
 */
class InvalidDraftNeverReachesTheGateTest {

    private OrchestratorHarness harness;

    private RunService service(LlmClient llm, ToolRegistry tools) {
        harness = OrchestratorHarness.of(tools, llm);
        return harness.service;
    }

    /** The write from the transcript: two required fields, neither of them content Relay may invent. */
    private static ToolRegistry jiraTools() {
        return new ToolRegistryImpl(List.of(new TestDoubles.NamedTool(
                "jira.createIssue", "jira", List.of("projectKey", "summary"), List.of("description"))));
    }

    /** A specialist that keeps answering with an object that is missing both required fields. */
    private static LlmClient forgetful(ToolRegistry tools, AtomicInteger turns) {
        return new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                    turns.incrementAndGet();
                    return new LlmResponse("{\"description\":\"Mailden geliyor.\"}",
                            10, 10, 0, "forgetful-stub", true);
                }
                return delegate.complete(request);
            }

            @Override
            public String name() {
                return "forgetful-stub";
            }

            @Override
            public boolean degraded() {
                return true;
            }
        };
    }

    @Test
    void a_draft_that_fails_its_own_schema_never_reaches_the_approval_gate() {
        ToolRegistry tools = jiraTools();
        AtomicInteger turns = new AtomicInteger();
        RunService service = service(forgetful(tools, turns), tools);

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        assertThat(harness.events.ofType(RunEvent.STEP_AWAITING))
                .as("a draft the tool would refuse is never put in front of a person")
                .isEmpty();
        assertThat(run.steps())
                .noneSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL));
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void the_step_fails_naming_the_fields_the_specialist_could_not_produce() {
        ToolRegistry tools = jiraTools();
        RunService service = service(forgetful(tools, new AtomicInteger()), tools);

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(run.steps().get(0).error())
                .as("the reason names the missing fields, not just 'invalid'")
                .contains("projectKey")
                .contains("summary");
    }

    @Test
    void the_specialist_is_given_its_retries_and_no_more() {
        ToolRegistry tools = jiraTools();
        AtomicInteger turns = new AtomicInteger();
        RunService service = service(forgetful(tools, turns), tools);

        service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        assertThat(turns.get())
                .as("one draft plus the retries Step.MAX_RETRIES already allows — not a loop")
                .isLessThanOrEqualTo(Step.MAX_RETRIES + 1);
    }

    @Test
    void a_specialist_that_fixes_itself_still_reaches_the_gate() {
        ToolRegistry tools = jiraTools();
        AtomicInteger turns = new AtomicInteger();
        LlmClient improving = new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                    String content = turns.getAndIncrement() == 0
                            ? "{\"description\":\"Mailden geliyor.\"}"
                            : "{\"projectKey\":\"KAN\",\"summary\":\"Fatura akışı hata veriyor\"}";
                    return new LlmResponse(content, 10, 10, 0, "improving-stub", true);
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

        Run run = service.startFromPlaybook("Maili işe çevir", "test",
                List.of(new RunService.SeedStep("Jira kaydını aç", "jira.createIssue", Map.of())),
                1.0);

        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the repaired step should be at the gate"));
        assertThat(gate.params()).containsEntry("projectKey", "KAN");
        assertThat(gate.params()).containsEntry("summary", "Fatura akışı hata veriyor");
    }
}
