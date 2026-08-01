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
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Relay's interface is Turkish end to end, and the two places a user reads the most are
 * error boxes and the grounds on the approval card. Both answered in English.
 *
 * <p>A 2 500 character goal came back as "goal is too long: 2500 characters, limit is
 * 2000"; an empty one as "goal is required"; a mistyped run id as "'id' değeri geçersiz",
 * which is Turkish about a Java parameter name the reader has never seen. Beside them the
 * same endpoint answered other faults in Turkish, so the product looked half-translated
 * rather than technical.
 */
class TurkishMessagesTest {

    /** Strings that must not survive anywhere a person can read them (#81). */
    private static final List<String> ENGLISH =
            List.of("is too long", "is required", "not found", "write risk", "policy override");

    private RunController runs() {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        TestDoubles.InMemoryRunRepository store = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(store,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new RunController(
                new RunService(store, coordinator, journal, clock, Runnable::run, 1.0), null);
    }

    @Test
    void a_goal_that_is_empty_or_too_long_is_refused_in_turkish() {
        assertThatThrownBy(() -> runs().create(new RunController.CreateRunRequest("   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bir hedef yaz.");

        assertThatThrownBy(() -> runs()
                .create(new RunController.CreateRunRequest("a".repeat(2500), null)))
                .isInstanceOf(IllegalArgumentException.class)
                // The number the user needs is how long it is and how long it may be.
                .hasMessage("Hedef çok uzun — 2500 karakter, sınır 2000.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(ENGLISH));

        // A goal of exactly the limit is still a goal.
        assertThat(runs().create(new RunController.CreateRunRequest("a".repeat(2000), null))
                .getBody()).containsKey("runId");
    }

    /**
     * A broken link into Geçmiş is the reader's whole context; "'id' değeri geçersiz" hands
     * them the name of a method parameter instead of telling them what happened.
     */
    @Test
    void a_mistyped_run_id_is_answered_as_a_missing_run_not_as_a_bad_field() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, Object>> uuid = handler.badParameter(
                new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "id", null, null));
        assertThat(uuid.getBody().get("message"))
                .isEqualTo("Bu akış bulunamadı — bağlantı hatalı olabilir.");

        // A query parameter the caller typed themselves is worth naming: they can fix it.
        ResponseEntity<Map<String, Object>> query = handler.badParameter(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "size", null, null));
        assertThat(query.getBody().get("message")).asString().contains("size");
    }

    /** The record is gone or the flow moved on — both are sentences, not log lines. */
    @Test
    void a_missing_record_and_a_stale_screen_both_answer_in_turkish() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        Object missing = handler.notFound(new RunService.NotFound("run 2f1c not found"))
                .getBody().get("message");
        Object stale = handler.conflict(new RunService.Conflict("step 8c1f already finished as done"))
                .getBody().get("message");

        assertThat(missing).asString().doesNotContain(ENGLISH).contains("bulunamadı");
        assertThat(stale).asString().doesNotContain(ENGLISH).contains("yenile");
    }
}
