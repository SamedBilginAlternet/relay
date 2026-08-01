package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.assistant.AskService;
import com.relay.application.assistant.SourceRouter;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.application.view.Views;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Money is one number with one shape, whichever endpoint reports it.
 *
 * <p>It was three. {@code /api/runs} rounded on the way in and answered {@code 3.82E-4};
 * {@code /api/ask} added two doubles and answered {@code 0.0006177699999999999};
 * {@code /api/brief} did the same with {@code 0.0036615500000000004}. All three reached the
 * screen, and the panel's total moved by a fraction of a cent depending on which one a
 * reader had counted from. A price written in scientific notation is not a price.
 */
class MoneyOnTheWireTest {

    /** Whatever a caller was charged, six decimals and no exponent (#90). */
    @Test
    void every_cost_field_is_rounded_to_six_decimals() {
        assertThat(runCost()).isEqualTo(new BigDecimal("0.000618"));
        assertThat(stepCost()).isEqualTo(new BigDecimal("0.000618"));
        // Two turns — routing, then answering — added together: 0.0012355399999999998.
        assertThat(askCost()).isEqualTo(new BigDecimal("0.001236"));
        assertThat(briefCost()).isEqualTo(new BigDecimal("0.003662"));

        assertThat(plainJson(Views.runSummary(runCosting(0.000382)))).doesNotContain("E-");
        assertThat(plainJson(Map.of("costUsd", CostMeter.usd(0.000382))))
                .contains("0.000382");
    }

    /** A run with no ceiling still answers {@code null}, not {@code 0.000000}. */
    @Test
    void an_absent_budget_stays_absent() {
        assertThat(Views.runSummary(runCosting(0.0)).get("budgetUsd")).isNull();
        assertThat(CostMeter.usd(Double.POSITIVE_INFINITY)).isNull();
    }

    // ---- the three endpoints ----------------------------------------------

    private static Object runCost() {
        return Views.runSummary(runCosting(0.0006177699999999999)).get("costUsd");
    }

    private static Object stepCost() {
        Run run = runCosting(0.0);
        Step step = Step.create(run.id(), 1, "ara", "jira-agent", "jira.searchIssues", Map.of());
        step.addCost(1023, 0.0006177699999999999);
        run.addStep(step);
        return Views.step(step).get("costUsd");
    }

    private static Object askCost() {
        ToolRegistry tools = new ToolRegistryImpl(
                List.of(new GmailTool.Search("replay", new FixtureStore(), null)));
        LlmClient llm = new FixedCostLlm(0.0006177699999999999);
        AskService ask = new AskService(tools, new TestDoubles.InMemoryConnectionRepository(),
                new SourceRouter(llm, tools), llm);
        return ask.ask("Kargolarım gelmiş mi?").get("costUsd");
    }

    @SuppressWarnings("unchecked")
    private static Object briefCost() {
        // The brief's own shape: llm.costUsd is the insight turn plus the digest turn.
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", "groq");
        llm.put("tokens", 5685L);
        llm.put("costUsd", 0.0036615500000000004);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llm", llm);

        return ((Map<String, Object>) BriefController.priced(body).get("llm")).get("costUsd");
    }

    // ---- helpers ----------------------------------------------------------

    private static Run runCosting(double usd) {
        Run run = Run.create("blocker özeti", Instant.parse("2026-07-31T09:00:00Z"), null);
        run.addCost(1023, usd);
        return run;
    }

    private static String plainJson(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers nothing useful — the point is the number on the invoice, not the content. */
    private record FixedCostLlm(double costUsd) implements LlmClient {

        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse("cevap yok", 700, 323, costUsd, "fixed-cost", false);
        }

        @Override
        public String name() {
            return "fixed-cost";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }
}
