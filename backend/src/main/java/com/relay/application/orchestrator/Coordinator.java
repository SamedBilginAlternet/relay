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
    private final Summarizer summarizer;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Without a summarizer the run still finishes — it just ends on the cost line. */
    public Coordinator(RunRepository runs, Planner planner, ToolAgent toolAgent, Verifier verifier,
                       PolicyEngine policyEngine, CostMeter costMeter, EventPublisher events,
                       AgentJournal journal, Clock clock) {
        this(runs, planner, toolAgent, verifier, policyEngine, costMeter, events, journal, clock, null);
    }

    public Coordinator(RunRepository runs, Planner planner, ToolAgent toolAgent, Verifier verifier,
                       PolicyEngine policyEngine, CostMeter costMeter, EventPublisher events,
                       AgentJournal journal, Clock clock, Summarizer summarizer) {
        this.summarizer = summarizer;
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
            if (ToolAgent.ungrounded(outcome.error()) && !step.retriesExhausted()
                    && insertLookupBefore(run, step)) {
                return;
            }
            if (!step.retriesExhausted()) {
                retryWithProviderFeedback(run, step, outcome.error());
                return;
            }
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

    /**
     * Feeds a provider's rejection back into the step instead of ending the run on it.
     *
     * <p>Providers answer with the fix inside the error — "'Blocked' geçişi yok, mümkün
     * olanlar: Yapılacaklar, Devam Ediyor, İncelemede, Tamam". Failing there wastes an
     * answer the specialist could have used.
     *
     * <p>A write does not simply re-run: its parameters are about to change, and the human
     * approved the old ones. So the decision is cleared and the step goes back through the
     * approval gate — the promise is that no write executes on parameters nobody saw.
     */
    private void retryWithProviderFeedback(Run run, Step step, String error) {
        boolean write = policyEngine.evaluate(step.toolName()).ask();
        step.lastProviderError(error);
        step.sendBack();
        if (write) {
            step.decision(null);
            // Derive the corrected parameters now, so the approval screen shows what will
            // actually be sent rather than the values that just bounced.
            ToolAgent.ParamRefresh refresh = toolAgent.refreshParams(run, step);
            costMeter.record(run, step, refresh.tokens(), refresh.costUsd());
        }
        journal.say(run, step.id(), AgentRole.COORDINATOR, step.role(),
                "Araç hatayı gerekçesiyle döndürdü: " + error
                        + (write ? " Parametreler yeniden üretilip tekrar onayına gelecek."
                                 : " Parametreler hataya göre yeniden üretiliyor."));
        runs.save(run);
        publishCost(run);
    }

    /**
     * Repairs a plan that tried to write to a record it never looked up.
     *
     * <p>Rather than failing the run — which leaves the user with an error and no work done —
     * the coordinator puts a read step in front of the write and sends the write back. The
     * new step is visible in the plan like any other: the repair is part of the audit trail,
     * not a hidden retry.
     *
     * @return {@code true} when the plan was repaired and the loop should yield
     */
    private boolean insertLookupBefore(Run run, Step step) {
        String lookupTool = toolAgent.lookupToolFor(step.toolName()).orElse(null);
        if (lookupTool == null || alreadyPrecededByRead(run, step)) {
            return false;
        }

        Step lookup = Step.create(run.id(), step.ordinal(), "Önce ilgili kaydı bul",
                AgentRole.toolAgent(lookupTool), lookupTool, Map.of());
        List<Step> repaired = new ArrayList<>();
        for (Step existing : run.steps()) {
            if (existing.ordinal() >= step.ordinal()) {
                existing.ordinal(existing.ordinal() + 1);
            }
            repaired.add(existing);
        }
        repaired.add(lookup);
        repaired.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        run.replaceSteps(repaired);
        step.params(ToolAgent.withoutIdentifiers(step.params()));
        step.sendBack();

        journal.say(run, step.id(), AgentRole.COORDINATOR, AgentRole.USER,
                "Adım " + step.ordinal() + " kaydı adıyla değil varsayımla hedefliyordu."
                        + " Plana önce " + lookupTool + " adımı eklendi.");
        runs.save(run);
        events.publish(run.id(), RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", stepViews(run.steps()))));
        publishCost(run);
        return true;
    }

    /** Has a read of the same provider already run before this step? Then repair is pointless. */
    private boolean alreadyPrecededByRead(Run run, Step step) {
        String provider = step.toolName() == null ? "" : step.toolName().split("\\.")[0];
        return run.steps().stream()
                .filter(other -> other.ordinal() < step.ordinal())
                .anyMatch(other -> other.toolName() != null && other.toolName().startsWith(provider + "."));
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
        sayWhatHappened(run, status);
        journal.say(run, null, AgentRole.COORDINATOR, AgentRole.USER,
                "Akış bitti: " + status.wire() + String.format(" · %,d token · $%.4f", run.costTokens(), run.costUsd()));
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.wire());
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        events.publish(run.id(), RunEvent.of(RunEvent.RUN_FINISHED, data));
    }

    /**
     * The closing line the user actually reads. Costs one cheap call and is allowed to fail:
     * the run is already finished, so a missing summary changes nothing but the wording.
     */
    private void sayWhatHappened(Run run, RunStatus status) {
        if (summarizer == null || run.steps().isEmpty()) {
            return;
        }
        Summarizer.Outcome outcome = summarizer.summarise(run, status == RunStatus.FAILED);
        if (outcome == null) {
            return;
        }
        costMeter.record(run, null, outcome.tokens(), outcome.costUsd());
        journal.say(run, null, AgentRole.COORDINATOR, AgentRole.USER, outcome.text());
    }

    private List<Map<String, Object>> stepViews(List<Step> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        steps.forEach(s -> out.add(Views.step(s)));
        return out;
    }
}
