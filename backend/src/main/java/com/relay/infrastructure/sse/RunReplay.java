package com.relay.infrastructure.sse;

import com.relay.application.port.RunEvent;
import com.relay.application.view.Views;
import com.relay.domain.AgentMessage;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run's story told again from the database.
 *
 * <p>The live buffer is process memory, and it dies with the process. A deploy in the
 * middle of the afternoon used to leave every run that was waiting on a human with a
 * stream that answered nothing but keepalives: the screen kept its "Onay bekliyor" badge
 * over an empty timeline, and no reconnect could ever fix it, because there was nothing
 * left in memory to reconnect to.
 *
 * <p>Nothing is invented here. Steps and agent messages are persisted, so the frames are
 * rebuilt from the rows in the same shape the coordinator published them in — which is
 * why they come out of {@link Views}, exactly like the live ones.
 */
final class RunReplay {

    private RunReplay() {
    }

    /** Empty when the run has not said anything yet — there is no story to tell. */
    static List<RunEvent> of(Run run) {
        List<RunEvent> story = new ArrayList<>();
        if (run.steps().isEmpty() && run.messages().isEmpty()) {
            return story;
        }
        if (!run.steps().isEmpty()) {
            List<Map<String, Object>> steps = new ArrayList<>();
            run.steps().forEach(step -> steps.add(Views.step(step)));
            story.add(RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", steps)));
        }
        run.steps().forEach(step -> whereItGot(step).ifPresent(story::add));
        run.messages().stream()
                .sorted(Comparator.comparing(AgentMessage::createdAt,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                .forEach(message -> story.add(RunEvent.of(RunEvent.AGENT_MESSAGE, Views.message(message))));
        story.add(RunEvent.of(RunEvent.RUN_COST, cost(run)));
        if (run.status().terminal()) {
            story.add(RunEvent.of(RunEvent.RUN_FINISHED, finished(run)));
        }
        return story;
    }

    /**
     * The one frame that says where a step stopped.
     *
     * <p>Not the frames it went through: the row records the state it reached, not the
     * path, and a replay that guessed at the path would be telling a story the trail
     * cannot back up. A step that has not started yet says nothing — the plan frame
     * already listed it.
     */
    private static java.util.Optional<RunEvent> whereItGot(Step step) {
        return switch (step.status()) {
            case PENDING -> java.util.Optional.empty();
            case RUNNING -> java.util.Optional.of(RunEvent.of(RunEvent.STEP_STARTED, started(step)));
            case AWAITING_APPROVAL -> java.util.Optional.of(RunEvent.of(RunEvent.STEP_AWAITING, awaiting(step)));
            default -> java.util.Optional.of(RunEvent.of(RunEvent.STEP_FINISHED, finished(step)));
        };
    }

    private static Map<String, Object> started(Step step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("ordinal", step.ordinal());
        data.put("title", step.title());
        data.put("toolName", String.valueOf(step.toolName()));
        data.put("role", String.valueOf(step.role()));
        return data;
    }

    /**
     * The approval gate as it stands. {@code reason} — the sentence the coordinator wrote
     * when it parked — is not on the step, it is in the journal, and it is replayed there
     * as an {@code agent.message}. Making one up here would put words on the screen that
     * nobody said.
     */
    private static Map<String, Object> awaiting(Step step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("ordinal", step.ordinal());
        data.put("title", step.title());
        data.put("toolName", step.toolName());
        data.put("params", step.params());
        data.put("pausedBy", step.pausedBy() == null ? null : step.pausedBy().wire());
        return data;
    }

    private static Map<String, Object> finished(Step step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("status", step.status().wire());
        data.put("result", step.result());
        data.put("error", step.error());
        data.put("tokens", step.tokens());
        data.put("costUsd", step.costUsd());
        data.put("step", Views.step(step));
        return data;
    }

    private static Map<String, Object> cost(Run run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        data.put("budgetUsd", run.budgetUsd());
        return data;
    }

    private static Map<String, Object> finished(Run run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", run.status().wire());
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        data.put("finishedAt", Views.iso(run.finishedAt()));
        return data;
    }
}
