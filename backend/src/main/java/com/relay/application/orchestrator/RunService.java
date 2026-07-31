package com.relay.application.orchestrator;

import com.relay.application.port.Clock;
import com.relay.application.port.RunRepository;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Decision;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.util.List;
import java.util.Map;
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
    private final ToolRegistry tools;

    public RunService(RunRepository runs, Coordinator coordinator, AgentJournal journal, Clock clock,
                      Executor executor, Double defaultBudgetUsd) {
        this(runs, coordinator, journal, clock, executor, defaultBudgetUsd, null);
    }

    public RunService(RunRepository runs, Coordinator coordinator, AgentJournal journal, Clock clock,
                      Executor executor, Double defaultBudgetUsd, ToolRegistry tools) {
        this.runs = runs;
        this.coordinator = coordinator;
        this.journal = journal;
        this.clock = clock;
        this.executor = executor;
        this.defaultBudgetUsd = defaultBudgetUsd;
        this.tools = tools;
    }

    /** Long enough for a paragraph of context, short enough not to eat a run's budget. */
    private static final int MAX_GOAL_CHARS = 2000;

    /** Creates the run and returns immediately; planning and execution happen off-thread. */
    public Run start(String goal, Double budgetUsd) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        // The goal is pasted verbatim into every planner and specialist prompt, so an
        // unbounded one burns the run's budget before a single tool is called.
        if (goal.length() > MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("goal is too long: " + goal.length()
                    + " characters, limit is " + MAX_GOAL_CHARS);
        }
        Run run = Run.create(goal.trim(), clock.now(), budgetUsd != null ? budgetUsd : defaultBudgetUsd);
        runs.save(run);
        executor.execute(() -> coordinator.drive(run.id()));
        return run;
    }

    /**
     * A card on the Bugün screen turned into a real run (BRIEF §3).
     *
     * <p>The step is seeded instead of planned — the planner has nothing to decide, the user
     * already chose. Everything after that is identical to a typed goal: the coordinator
     * walks it, the policy engine sees the same WRITE risk and the same approval gate opens.
     * There is no fast path around the gate.
     */
    public Run startFromSuggestion(String toolName, Map<String, Object> params, String label,
                                   Double budgetUsd) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool is required");
        }
        if (tools != null && tools.find(toolName).isEmpty()) {
            throw new IllegalArgumentException("unknown tool: " + toolName);
        }
        String goal = label == null || label.isBlank() ? "Bugün önerisi: " + toolName : label.trim();

        Run run = Run.create(goal, clock.now(), budgetUsd != null ? budgetUsd : defaultBudgetUsd);
        run.addStep(Step.create(run.id(), 1, goal, AgentRole.toolAgent(toolName), toolName,
                params == null ? Map.of() : params));
        runs.save(run);
        journal.say(run, null, AgentRole.USER, AgentRole.COORDINATOR,
                "Bugün ekranından öneri çalıştırılıyor: " + toolName);
        executor.execute(() -> coordinator.drive(run.id()));
        return run;
    }

    /** One seeded step of a playbook: title, tool and starting parameters. */
    public record SeedStep(String title, String toolName, Map<String, Object> params) {
    }

    /**
     * Starts a written-down flow (a playbook) instead of asking the planner for a shape.
     *
     * <p>The steps are fixed, the facts are not: each specialist still finalises its own
     * parameters from what the earlier steps found, the policy engine still sees every
     * write, and the approval gate still opens. Skipping the planner removes one model call
     * and, with it, the run-to-run variance that made the same job come out differently
     * every time.
     */
    public Run startFromPlaybook(String goal, String label, List<SeedStep> steps, Double budgetUsd) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("a playbook needs at least one step");
        }
        Run run = Run.create(goal.trim(), clock.now(), budgetUsd != null ? budgetUsd : defaultBudgetUsd);
        int ordinal = 0;
        for (SeedStep seed : steps) {
            if (tools != null && tools.find(seed.toolName()).isEmpty()) {
                throw new IllegalArgumentException("unknown tool: " + seed.toolName());
            }
            run.addStep(Step.create(run.id(), ++ordinal, seed.title(),
                    AgentRole.toolAgent(seed.toolName()), seed.toolName(),
                    seed.params() == null ? Map.of() : seed.params()));
        }
        runs.save(run);
        journal.say(run, null, AgentRole.USER, AgentRole.COORDINATOR,
                "Hazır akış çalıştırılıyor: " + label + " (" + ordinal + " adım)");
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
