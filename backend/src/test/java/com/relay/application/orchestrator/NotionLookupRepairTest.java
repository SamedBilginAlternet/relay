package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.NotionTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The repair that could not exist while Notion had no READ.
 *
 * <p>{@code Coordinator.insertLookupBefore} fixes a write aimed at a record nobody looked up
 * by putting the provider's search tool in front of it — but {@code ToolAgent.lookupToolFor}
 * finds that tool by risk and name, and for notion there was nothing to find: the provider
 * shipped write-only, {@code pageId} was waved through as a container <em>because</em> no
 * lookup could ground it, and the owner met the gap as a sentence — <em>"notion için kayıtlı
 * bir okuma aracı yok."</em> ({@code ConnectionService.test}, the same no-READ registry
 * state). A {@code notion.appendToPage} whose page came from neither the goal nor the
 * connection was therefore unrescuable: the invented id went to Notion, Notion said
 * {@code object_not_found}, and the retry had nowhere new to get a page from.
 *
 * <p>With {@code notion.search} registered, an invented pageId takes the jira path instead:
 * refused as ungrounded, repaired with a visible lookup step, re-approved on the page the
 * search actually found. This test is that whole story, end to end.
 */
class NotionLookupRepairTest {

    /** The page the fixture answers with — the "Karar kütüğü" the goal talks about. */
    private static final String FOUND_PAGE = "2f0a1b9c4d5e4f60a1b2c3d4e5f60718";

    /** 32 hex digits from nowhere: not in the goal, not in any result, not on a connection. */
    private static final String INVENTED_PAGE = "44440000aaaa4bbb8ccc0123456789ab";

    /** Plans one ungrounded Notion write; afterwards answers whatever each caller needs. */
    private static class ScriptedLlm implements LlmClient {

        @Override
        public LlmResponse complete(LlmRequest request) {
            String content = switch (request.purpose()) {
                case LlmPurpose.PLAN -> """
                        {"steps":[{"title":"Kararı kütüğe ekle","toolName":"notion.appendToPage",
                          "params":{"pageId":"%s",
                                    "content":"Karar: Aras Kargo fatura itirazı kabul edildi."}}]}
                        """.formatted(INVENTED_PAGE);
                // The specialist reads the page id off the search step that just ran.
                case LlmPurpose.TOOL_PARAMS -> """
                        {"pageId":"%s","content":"Karar: Aras Kargo fatura itirazı kabul edildi."}
                        """.formatted(FOUND_PAGE);
                default -> "{\"pass\":true,\"reason\":\"tamam\"}";
            };
            return new LlmResponse(content, 100, 50, 0.0002, "scripted", false);
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

    private Run runGoal() {
        FixtureStore fixtures = new FixtureStore();
        OrchestratorHarness harness = OrchestratorHarness.of(
                new ToolRegistryImpl(List.of(
                        new NotionTool.Search("replay", fixtures),
                        new NotionTool.AppendToPage("replay", fixtures))),
                new ScriptedLlm());

        Run run = harness.service.start("Fatura itirazı kararını karar kütüğüne ekle", null);
        // The write parks on the approval gate; the human says yes — before and after repair.
        for (int guard = 0; guard < 4 && run.status() == RunStatus.AWAITING_APPROVAL; guard++) {
            Step parked = List.copyOf(run.steps()).stream()
                    .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElse(null);
            if (parked == null) {
                break;
            }
            harness.service.approve(run.id(), parked.id());
        }
        return run;
    }

    @Test
    void a_lookup_step_is_inserted_in_front_of_the_ungrounded_notion_write() {
        Run run = runGoal();

        List<Step> steps = run.steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).toolName()).isEqualTo("notion.search");
        assertThat(steps.get(0).ordinal()).isEqualTo(1);
        assertThat(steps.get(0).status()).isEqualTo(StepStatus.DONE);
        assertThat(steps.get(1).toolName()).isEqualTo("notion.appendToPage");
        assertThat(steps.get(1).ordinal()).isEqualTo(2);
    }

    @Test
    void the_repaired_write_targets_the_page_the_search_actually_found() {
        Run run = runGoal();

        Step write = run.steps().get(1);
        assertThat(write.params()).containsEntry("pageId", FOUND_PAGE);
        assertThat(write.params()).doesNotContainEntry("pageId", INVENTED_PAGE);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /** The repair is announced, not slipped in — the audit trail has to show it. */
    @Test
    void the_repair_is_written_into_the_journal() {
        Run run = runGoal();

        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("notion.search adımı eklendi"));
    }
}
