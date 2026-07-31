package com.relay.application.orchestrator;

import com.relay.application.port.Clock;
import com.relay.application.port.RunRepository;
import com.relay.domain.AgentRole;
import com.relay.domain.Decision;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * What the API layer is allowed to do with runs. Controllers hold no business logic;
 * everything they need is one call here.
 */
public class RunService {

    private final RunRepository runs;
    private final Coordinator coordinator;
    private final AgentJournal journal;
    private final Clock clock;
    private final Executor executor;
    private final Double defaultBudgetUsd;

    public RunService(RunRepository runs, Coordinator coordinator, AgentJournal journal, Clock clock,
                      Executor executor, Double defaultBudgetUsd) {
        this.runs = runs;
        this.coordinator = coordinator;
        this.journal = journal;
        this.clock = clock;
        this.executor = executor;
        this.defaultBudgetUsd = defaultBudgetUsd;
    }

    /** Creates the run and returns immediately; planning and execution happen off-thread. */
    public Run start(String goal, Double budgetUsd) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        Run run = Run.create(goal.trim(), clock.now(), budgetUsd != null ? budgetUsd : defaultBudgetUsd);
        runs.save(run);
        executor.execute(() -> coordinator.drive(run.id()));
        return run;
    }

    public Run get(UUID id) {
        return runs.findById(id).orElseThrow(() -> new NotFound("run " + id + " not found"));
    }

    public List<Run> list(int page, int size) {
        return runs.findAll(page, size);
    }

    public long count() {
        return runs.count();
    }

    public Run approve(UUID runId, UUID stepId) {
        Run run = get(runId);
        Step step = step(run, stepId);
        if (step.status() != StepStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("step " + stepId + " is not awaiting approval");
        }
        step.approve();
        if (run.overBudget()) {
            // The pause was a budget pause: approving it raises the ceiling for this run.
            run.budgetOverridden(true);
        }
        run.status(RunStatus.RUNNING);
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                "Onaylandı — devam et.");
        runs.save(run);
        executor.execute(() -> coordinator.drive(runId));
        return run;
    }

    public Run reject(UUID runId, UUID stepId, String reason) {
        Run run = get(runId);
        Step step = step(run, stepId);
        if (step.status().terminal()) {
            throw new IllegalStateException("step " + stepId + " already finished");
        }
        String why = reason == null || reason.isBlank() ? "kullanıcı reddetti" : reason.trim();
        step.decision(Decision.REJECTED);
        step.rejectReason(why);
        // Left PENDING on purpose: the coordinator turns the rejection into a terminal
        // REJECTED step, records it on the timeline and tells the agent why.
        step.status(StepStatus.PENDING);
        run.status(RunStatus.RUNNING);
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                "Reddedildi: " + why);
        runs.save(run);
        executor.execute(() -> coordinator.drive(runId));
        return run;
    }

    /** Same goal, brand new run. */
    public Run rerun(UUID runId) {
        Run original = get(runId);
        return start(original.goal(), original.budgetUsd());
    }

    /** Blocking variant — used by tests and by any caller that wants the finished run. */
    public void driveNow(UUID runId) {
        coordinator.drive(runId);
    }

    private Step step(Run run, UUID stepId) {
        return run.step(stepId).orElseThrow(() -> new NotFound("step " + stepId + " not found"));
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }
}
