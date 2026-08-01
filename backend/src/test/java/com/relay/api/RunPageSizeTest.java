package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A paged response has to describe itself honestly, because the client cannot see the
 * rows it did not get.
 *
 * <p>Live, {@code /api/runs?size=99999} answered 100 items and {@code "size": 99999} over
 * 114 recorded runs. Dividing total by the size it was told gives one page, so the history
 * screen believed it held everything and fourteen runs were unreachable — with no error, no
 * warning and nothing in the payload that contradicted it.
 */
class RunPageSizeTest {

    private static final int RECORDED = 114;

    private RunController controller() {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
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

        for (int i = 0; i < RECORDED; i++) {
            runs.save(Run.create("akış " + i, Instant.parse("2026-07-31T09:00:00Z").plusSeconds(i), null));
        }
        return new RunController(
                new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0), null);
    }

    @SuppressWarnings("unchecked")
    private static int returned(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("items")).size();
    }

    @Test
    void the_reported_page_size_always_matches_what_was_returned() {
        RunController runs = controller();

        for (int asked : new int[] {99999, 100, 20, 1, 0, -3}) {
            Map<String, Object> body = runs.list(0, asked);
            assertThat(body.get("size"))
                    .as("reported size for ?size=%d", asked)
                    .isEqualTo(returned(body));
        }

        Map<String, Object> unbounded = runs.list(0, 99999);
        assertThat(unbounded.get("size")).isEqualTo(RunController.MAX_PAGE_SIZE);
        assertThat(returned(unbounded)).isEqualTo(RunController.MAX_PAGE_SIZE);
        // The count a client pages with stays the truth: 114 runs, 100 to a page, two pages.
        assertThat(unbounded.get("total")).isEqualTo((long) RECORDED);
        assertThat(returned(runs.list(1, 99999))).isEqualTo(RECORDED - RunController.MAX_PAGE_SIZE);
    }

    /** "Zero records" is not something a caller can mean, and it never returned zero. */
    @Test
    void a_size_below_one_is_pulled_to_one_row_not_answered_with_one_by_accident() {
        Map<String, Object> body = controller().list(0, 0);

        assertThat(body.get("size")).isEqualTo(1);
        assertThat(returned(body)).isEqualTo(1);
    }

    /** A negative offset is the caller's mistake; 200 would hide it inside ours. */
    @Test
    void a_negative_page_is_refused_instead_of_silently_corrected() {
        assertThatThrownBy(() -> controller().list(-1, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sayfa numarası");
    }
}
