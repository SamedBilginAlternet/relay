package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Run {@code 85f1b3be}, 2026-08-01. Goal: append a quoted note to the Notion decision
 * log. Plan: one step, {@code jira.updateIssue} with status Done on KAN-32 — the
 * planner keyword-matched the note's own payload. The human at the gate saw a plausible
 * Jira write, nothing told them the goal had asked for Notion, and KAN-32 was wrongly
 * closed (#175; reverted in run {@code 790f5d65}).
 *
 * <p>These tests hold the surfacing, not the arithmetic ({@link PlanCoverageTest} holds
 * that): a drifted plan's write step carries the warning the gate will draw, the journal
 * says it out loud, and — just as important — a clean plan carries neither. In v1 the run
 * is deliberately not blocked; the warning meets the human at the write gate every WRITE
 * step already passes through.
 */
class PlanDriftWarningTest {

    private static final String GOAL =
            "Karar kütüğü sayfasına şu notu ekle: '1 Ağustos uçtan uca canlı test tamamlandı"
                    + " — Günü kapat akışı KAN-32 ile dört kapıdan geçti…'";

    private record FakeTool(String toolName, RiskLevel riskLevel) implements Tool {
        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public JsonNode schema() {
            return Json.object().put("type", "object");
        }

        @Override
        public RiskLevel risk() {
            return riskLevel;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            return ToolResult.ok(Json.object().put("ok", true), 2, "replay");
        }
    }

    /** Answers the planner with whatever plan the test is about; everything else passes. */
    private record ScriptedLlm(String planJson) implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            String content;
            if (LlmPurpose.PLAN.equals(request.purpose())) {
                content = planJson;
            } else if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                content = request.user().contains("TOOL: notion.search")
                        ? "{\"query\":\"karar kütüğü\"}"
                        : request.user().contains("TOOL: notion.appendToPage")
                                ? "{\"pageId\":\"pg-1\",\"content\":\"1 Ağustos canlı test notu\"}"
                                : "{\"issueKey\":\"KAN-32\",\"status\":\"Done\"}";
            } else {
                content = "{\"pass\":true,\"reason\":\"tamam\"}";
            }
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

    private static OrchestratorHarness harness(String planJson) {
        return OrchestratorHarness.of(
                new ToolRegistryImpl(List.of(
                        new FakeTool("jira.updateIssue", RiskLevel.WRITE),
                        new FakeTool("notion.search", RiskLevel.READ),
                        new FakeTool("notion.appendToPage", RiskLevel.WRITE))),
                new ScriptedLlm(planJson));
    }

    @Test
    void the_drifted_write_carries_the_warning_the_gate_will_draw() {
        OrchestratorHarness h = harness("""
                {"steps":[{"title":"KAN-32'yi Done'a taşı","role":"jira-agent",
                  "toolName":"jira.updateIssue",
                  "params":{"issueKey":"KAN-32","status":"Done"}}]}""");

        Run run = h.service.start(GOAL, 1.0);

        Step write = run.steps().get(0);
        assertThat(write.warning())
                .as("the sentence the approving human must trip over")
                .contains("hedefte anılmayan bir yüzeye yazıyor")
                .contains("Jira");
        assertThat(run.messages())
                .as("and the journal says it out loud, in Turkish")
                .anyMatch(m -> m.content().equals(
                        "Uyarı: plan, hedefte anılmayan bir yüzeye yazıyor: Jira."))
                .anyMatch(m -> m.content().contains(
                        "hedefte Notion anılıyor ama planda hiçbir Notion adımı yok"));
        assertThat(run.status())
                .as("v1 warns, it does not block: the run still walks to the write gate")
                .isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void a_clean_plan_carries_no_warning_and_no_journal_alarm() {
        OrchestratorHarness h = harness("""
                {"steps":[
                  {"title":"Karar kütüğü sayfasını bul","role":"notion-agent",
                   "toolName":"notion.search","params":{"query":"karar kütüğü"}},
                  {"title":"Notu sayfaya ekle","role":"notion-agent",
                   "toolName":"notion.appendToPage","params":{}}]}""");

        Run run = h.service.start(GOAL, 1.0);

        assertThat(run.steps()).allSatisfy(step -> assertThat(step.warning()).isNull());
        assertThat(run.messages())
                .noneMatch(m -> m.content().startsWith("Uyarı:"));
    }

    @Test
    void the_warning_survives_the_round_trip_to_the_approval_request() {
        OrchestratorHarness h = harness("""
                {"steps":[{"title":"KAN-32'yi Done'a taşı","role":"jira-agent",
                  "toolName":"jira.updateIssue",
                  "params":{"issueKey":"KAN-32","status":"Done"}}]}""");

        Run run = h.service.start(GOAL, 1.0);

        // Approval arrives in a different request: what it reads is the repository's copy.
        Run reread = h.runs.findById(run.id()).orElseThrow();
        assertThat(reread.steps().get(0).warning())
                .contains("hedefte anılmayan bir yüzeye yazıyor");
    }
}
