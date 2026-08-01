package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists: 2026-08-01, twice in one day.
 *
 * <p>Run {@code 85f1b3be}: the goal named the Notion decision log and quoted a note to
 * append; the note happened to contain "KAN-32" and "tamamlandı", the planner executed the
 * payload as a command, and the one-step plan closed KAN-32 in Jira. Approved at the gate,
 * reverted by hand (#175). And #145's mirror finding: a goal naming the mailbox and Jira
 * got a plan covering a quarter of it, and the run closed green.
 *
 * <p>These tests hold the deterministic check to both incidents' exact goals — and, just
 * as deliberately, to the goals it must stay silent on. A coverage warning that cries wolf
 * on every run teaches people to approve past it, which un-writes the one line it exists
 * to make them read.
 */
class PlanCoverageTest {

    /** The exact goal of run 85f1b3be. The record key sits INSIDE the quoted payload. */
    private static final String INCIDENT_A_GOAL =
            "Karar kütüğü sayfasına şu notu ekle: '1 Ağustos uçtan uca canlı test tamamlandı"
                    + " — Günü kapat akışı KAN-32 ile dört kapıdan geçti…'";

    /** The goal of #145's finding: mailbox and Jira project named, neither planned. */
    private static final String INCIDENT_B_GOAL =
            "maillerime bak, hata bildirimi olanlar için Jira'da kayıt aç";

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
            return ToolResult.ok(Json.object(), 0, "test");
        }
    }

    private static PlanCoverage coverage() {
        return new PlanCoverage(new ToolRegistryImpl(List.of(
                new FakeTool("jira.updateIssue", RiskLevel.WRITE),
                new FakeTool("jira.createIssue", RiskLevel.WRITE),
                new FakeTool("jira.searchIssues", RiskLevel.READ),
                new FakeTool("notion.search", RiskLevel.READ),
                new FakeTool("notion.appendToPage", RiskLevel.WRITE),
                new FakeTool("gmail.listToday", RiskLevel.READ))));
    }

    private static Step step(String toolName) {
        return Step.create(UUID.randomUUID(), 1, toolName, "agent", toolName, Map.of());
    }

    @Test
    void incident_a_the_bad_plan_writes_to_jira_which_the_goal_never_named() {
        PlanCoverage.Assessment drift = coverage().assess(INCIDENT_A_GOAL,
                List.of(step("jira.updateIssue")));

        assertThat(drift.unrequestedWrites()).contains("jira");
        assertThat(drift.missing()).contains("notion");
    }

    @Test
    void incident_a_the_good_plan_raises_no_warning_at_all() {
        PlanCoverage.Assessment drift = coverage().assess(INCIDENT_A_GOAL,
                List.of(step("notion.search"), step("notion.appendToPage")));

        assertThat(drift.missing()).isEmpty();
        assertThat(drift.unrequestedWrites()).isEmpty();
        assertThat(drift.clean()).isTrue();
    }

    @Test
    void a_record_key_inside_the_quoted_payload_is_content_not_a_jira_mention() {
        // The whole of incident A in one assertion: had "KAN-32" in the note counted as
        // naming Jira, the bad plan's Jira write would have looked requested.
        assertThat(PlanCoverage.mentionedProviders(INCIDENT_A_GOAL))
                .contains("notion")
                .doesNotContain("jira");
    }

    @Test
    void incident_b_a_plan_covering_a_quarter_of_the_goal_names_what_it_skipped() {
        // The plan that closed green: one reasoning step, no tool ever touched.
        Step reasoning = Step.create(UUID.randomUUID(), 1, "Hedefi özetle", "coordinator",
                null, Map.of());

        PlanCoverage.Assessment drift = coverage().assess(INCIDENT_B_GOAL, List.of(reasoning));

        assertThat(drift.missing()).contains("gmail", "jira");
        assertThat(drift.unrequestedWrites()).isEmpty();
    }

    @Test
    void incident_b_reading_the_mailbox_still_leaves_the_jira_half_missing() {
        PlanCoverage.Assessment drift = coverage().assess(INCIDENT_B_GOAL,
                List.of(step("gmail.listToday")));

        assertThat(drift.missing()).containsExactly("jira");
    }

    @Test
    void turkish_case_folds_the_locale_way_so_uppercase_dotted_and_dotless_i_both_match() {
        // JİRA → jira only under tr folding (ROOT leaves a combining dot behind), and
        // TAKVIM's dotless I → ı means the stem must be matched after tr folding too.
        assertThat(PlanCoverage.mentionedProviders("JİRA'DAKİ işleri TAKVİME yaz"))
                .contains("jira", "calendar");
        assertThat(PlanCoverage.mentionedProviders("KÜTÜĞE not düş"))
                .contains("notion");
    }

    @Test
    void ambiguous_words_alone_map_to_no_provider_and_raise_no_warning() {
        // "kayıt" is a Jira issue, a Sheets row or a generic record; "sayfa" is Notion,
        // Confluence or the web. A checker that guessed here would cry wolf on every run —
        // a missed warning is recoverable, a warning nobody believes is not.
        String vague = "kayıt aç ve sayfayı güncelle";

        assertThat(PlanCoverage.mentionedProviders(vague)).isEmpty();
        assertThat(coverage().assess(vague, List.of(step("jira.createIssue"))).clean())
                .isTrue();
    }

    @Test
    void a_goal_naming_no_surface_leaves_the_planner_free_to_choose_one() {
        PlanCoverage.Assessment drift = coverage().assess(
                "bugün ne yaptığımızı ekibe kısaca duyur",
                List.of(step("jira.searchIssues"), step("jira.createIssue")));

        assertThat(drift.clean()).isTrue();
    }
}
