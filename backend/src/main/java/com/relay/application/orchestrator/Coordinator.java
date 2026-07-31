package com.relay.application.orchestrator;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyDecision;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.Clock;
import com.relay.application.port.EventPublisher;
import com.relay.application.port.RunEvent;
import com.relay.application.port.RunRepository;
import com.relay.application.view.Views;
import com.relay.domain.AgentRole;
import com.relay.domain.Decision;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The crew lead. Plans once, then walks the steps: consults the policy engine,
 * hands each step to a tool agent, sends the result to the verifier and emits an
 * event on every transition.
 *
 * <p>The loop is re-entrant by design: it stops at the first step that needs a human
 * and resumes from exactly there when {@code approve}/{@code reject} calls it again.
 */
public class Coordinator {

    private static final Logger LOG = System.getLogger(Coordinator.class.getName());

    private final RunRepository runs;
    private final Planner planner;
    private final ToolAgent toolAgent;
    private final Verifier verifier;
    private final PolicyEngine policyEngine;
    private final CostMeter costMeter;
    private final EventPublisher events;
    private final AgentJournal journal;
    private final Clock clock;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Coordinator(RunRepository runs, Planner planner, ToolAgent toolAgent, Verifier verifier,
                       PolicyEngine policyEngine, CostMeter costMeter, EventPublisher events,
                       AgentJournal journal, Clock clock) {
        this.runs = runs;
        this.planner = planner;
        this.toolAgent = toolAgent;
        this.verifier = verifier;
        this.policyEngine = policyEngine;
        this.costMeter = costMeter;
        this.events = events;
        this.journal = journal;
        this.clock = clock;
    }

    /** Drives the run as far as it can go right now. Safe to call repeatedly. */
    public void drive(UUID runId) {
        ReentrantLock lock = locks.computeIfAbsent(runId, k -> new ReentrantLock());
        lock.lock();
        try {
            Run run = runs.findById(runId).orElse(null);
            if (run == null) {
                LOG.log(Level.WARNING, "drive: run {0} not found", runId);
                return;
            }
            if (run.status().terminal()) {
                return;
            }
            walk(run);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "run " + runId + " blew up", e);
            runs.findById(runId).ifPresent(run -> {
                journal.say(run, null, AgentRole.COORDINATOR, AgentRole.USER,
                        "Akış hata ile durdu: " + e.getMessage());
                finish(run, RunStatus.FAILED);
            });
        } finally {
            lock.unlock();
            locks.remove(runId, lock);
        }
    }

    // -----------------------------------------------------------------------

    private void walk(Run run) {
        if (run.steps().isEmpty()) {
            run.status(RunStatus.PLANNING);
            runs.save(run);
            List<Step> steps = planner.plan(run);
            run.status(RunStatus.RUNNING);
            runs.save(run);
            events.publish(run.id(), RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", stepViews(steps))));
            publishCost(run);
        }

        run.status(RunStatus.RUNNING);

        while (true) {
            Optional<Step> next = run.nextActionable();
            if (next.isEmpty()) {
                break;
            }
            Step step = next.get();

            if (step.decision() == Decision.REJECTED) {
                rejectStep(run, step, step.rejectReason() == null ? "kullanıcı reddetti" : step.rejectReason());
                continue;
            }

            if (step.status() == StepStatus.AWAITING_APPROVAL) {
                // Already parked on a human. Nothing to do until approve/reject arrives.
                run.status(RunStatus.AWAITING_APPROVAL);
                runs.save(run);
                return;
            }

            PolicyDecision policy = policyEngine.evaluate(step.toolName());
            if (policy.forbidden()) {
                journal.say(run, step.id(), AgentRole.POLICY, AgentRole.COORDINATOR,
                        "YASAK — " + policy.reason() + ". Adım reddedildi ve iz kaydına yazıldı.");
                rejectStep(run, step, "policy forbidden: " + policy.reason());
                continue;
            }

            if (policy.ask() && step.decision() != Decision.APPROVED) {
                park(run, step, "onay gerekiyor — " + policy.reason(), AgentRole.COORDINATOR);
                return;
            }

            if (costMeter.budgetExceeded(run)) {
                park(run, step, String.format("bütçe aşıldı: %.4f USD / %.4f USD limit — devam için onay gerekiyor",
                        run.costUsd(), run.budgetUsd()), AgentRole.COST);
                return;
            }

            if (step.decision() == null) {
                step.decision(Decision.AUTO);
            }

            runStep(run, step);

            if (step.status() == StepStatus.FAILED) {
                finish(run, RunStatus.FAILED);
                return;
            }
        }

        boolean anyFailed = run.steps().stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        finish(run, anyFailed ? RunStatus.FAILED : RunStatus.DONE);
    }

    private void runStep(Run run, Step step) {
        step.markRunning(clock.now());
        runs.save(run);
        events.publish(run.id(), RunEvent.of(RunEvent.STEP_STARTED, Map.of(
                "stepId", step.id().toString(),
                "ordinal", step.ordinal(),
                "title", step.title(),
                "toolName", String.valueOf(step.toolName()),
                "role", String.valueOf(step.role()))));

        StepOutcome outcome = toolAgent.execute(run, step);
        costMeter.record(run, step, outcome.tokens(), outcome.costUsd());

        if (!outcome.ok()) {
            step.markFailed(outcome.error(), clock.now());
            journal.say(run, step.id(), AgentRole.COORDINATOR, AgentRole.USER,
                    "Adım " + step.ordinal() + " başarısız: " + outcome.error());
            publishStepFinished(run, step);
            return;
        }

        Verifier.Verdict verdict = verifier.verify(run, step, outcome.result());
        costMeter.record(run, step, verdict.tokens(), verdict.costUsd());

        if (!verdict.pass()) {
            if (!step.retriesExhausted()) {
                step.sendBack();
                journal.say(run, step.id(), AgentRole.VERIFIER, step.role(),
                        "Geri gönderildi (" + step.attempts() + "/" + Step.MAX_RETRIES + "): " + verdict.reason());
                runs.save(run);
                publishCost(run);
                return;
            }
            step.markFailed("verification failed: " + verdict.reason(), clock.now());
            journal.say(run, step.id(), AgentRole.VERIFIER, AgentRole.COORDINATOR,
                    "Doğrulama iki denemede de geçmedi: " + verdict.reason());
            publishStepFinished(run, step);
            return;
        }

        step.markDone(outcome.result(), clock.now());
        journal.say(run, step.id(), AgentRole.VERIFIER, AgentRole.COORDINATOR,
                "Adım " + step.ordinal() + " doğrulandı: " + verdict.reason());
        publishStepFinished(run, step);
    }

    private void park(Run run, Step step, String reason, String from) {
        step.markAwaitingApproval();
        run.status(RunStatus.AWAITING_APPROVAL);
        journal.say(run, step.id(), from, AgentRole.USER, "Adım " + step.ordinal() + " — " + reason);
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("ordinal", step.ordinal());
        data.put("title", step.title());
        data.put("toolName", step.toolName());
        data.put("params", step.params());
        data.put("reason", reason);
        events.publish(run.id(), RunEvent.of(RunEvent.STEP_AWAITING, data));
    }

    private void rejectStep(Run run, Step step, String reason) {
        step.reject(reason);
        step.finishedAt(clock.now());
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                "Adım reddedildi: " + reason);
        publishStepFinished(run, step);
    }

    private void publishStepFinished(Run run, Step step) {
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("status", step.status().wire());
        data.put("result", step.result());
        data.put("error", step.error());
        data.put("tokens", step.tokens());
        data.put("costUsd", step.costUsd());
        data.put("step", Views.step(step));
        events.publish(run.id(), RunEvent.of(RunEvent.STEP_FINISHED, data));
        publishCost(run);
    }

    private void publishCost(Run run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        data.put("budgetUsd", run.budgetUsd());
        events.publish(run.id(), RunEvent.of(RunEvent.RUN_COST, data));
    }

    private void finish(Run run, RunStatus status) {
        run.status(status);
        run.finishedAt(clock.now());
        journal.say(run, null, AgentRole.COORDINATOR, AgentRole.USER,
                "Akış bitti: " + status.wire() + String.format(" · %,d token · $%.4f", run.costTokens(), run.costUsd()));
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.wire());
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        events.publish(run.id(), RunEvent.of(RunEvent.RUN_FINISHED, data));
    }

    private List<Map<String, Object>> stepViews(List<Step> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        steps.forEach(s -> out.add(Views.step(s)));
        return out;
    }
}
