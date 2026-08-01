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
import com.relay.domain.PauseReason;
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
import java.util.function.Supplier;

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
    private final RunLocks locks = new RunLocks();
    /**
     * Runs whose owner pressed Durdur, mapped to whoever pressed it.
     *
     * <p>Deliberately in memory and not on the run: it is a request that lives for seconds,
     * and the driving thread — which may be mid tool call with its own copy of the aggregate
     * — would overwrite anything written to the row underneath it.
     */
    private final Map<UUID, String> cancelRequests = new ConcurrentHashMap<>();

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
        try (RunLocks.Lease lease = locks.acquire(runId)) {
            // The recovery below writes the aggregate too, so it stays inside the lease
            // rather than in a catch around it.
            try {
                Run run = runs.findById(runId).orElse(null);
                if (run == null) {
                    LOG.log(Level.WARNING, "drive: run {0} not found", runId);
                    return;
                }
                if (run.status().terminal()) {
                    return;
                }
                if (stopIfCancelled(run)) {
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
            }
        }
    }

    /**
     * Stops a run on the user's word, at the first moment it is safe to stop.
     *
     * <p>A tool call that is already in flight is <b>not</b> interrupted. Aborting the HTTP
     * request would not undo anything: the provider may well have created the issue or
     * posted the message and only the answer would be lost — and a run whose trail says
     * "iptal edildi" over a write that actually happened is worse than a run that took ten
     * more seconds to stop. So the call is allowed to return and be recorded; what
     * cancellation guarantees is that <b>nothing new starts</b>.
     *
     * <p>Which is also why this never blocks on the run's lock: the driving thread holds it
     * for the whole tool call, and the user pressing Durdur must not wait on the very call
     * they are trying to get away from.
     *
     * @param actor the signed-in e-mail, written into the trail
     * @return {@code true} when the run was stopped and written down here and now,
     *         {@code false} when a step is in flight and the driving thread will close the
     *         run as soon as that step returns
     */
    public boolean cancel(UUID runId, String actor) {
        cancelRequests.put(runId, actor == null ? "" : actor);
        Optional<RunLocks.Lease> claimed = locks.tryAcquire(runId);
        if (claimed.isEmpty()) {
            return false;
        }
        try (RunLocks.Lease lease = claimed.get()) {
            Run run = runs.findById(runId).orElse(null);
            if (run == null || run.status().terminal()) {
                // Nothing to stop — it finished on its own between the check and the press.
                cancelRequests.remove(runId);
                return true;
            }
            stop(run);
            return true;
        }
    }

    /**
     * Runs a decision about a run — approve, reject — under the same lock the driver uses.
     *
     * <p>Without it, two people pressing Onayla on the same step both read a step that was
     * awaiting approval, both wrote "Onaylandı" into the trail and both saved. The tool ran
     * once (the driver's lock saw to that), so the record showed a decision that decided
     * nothing — on a shared workspace, two names against one action. The trail's whole claim
     * is "kim, neyi, neden", and an approval nobody acted on is none of the three.
     *
     * <p>It waits rather than refuses: the only long holder of this lock is a driving thread
     * mid tool call, and a step that is sitting at a gate has no driver. Durdur still does
     * not wait — see {@link #cancel}.
     */
    public <T> T decide(UUID runId, Supplier<T> decision) {
        try (RunLocks.Lease lease = locks.acquire(runId)) {
            return decision.get();
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
            if (stopIfCancelled(run)) {
                return;
            }
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

            // Money first, permission second. The other way round, a run that had already
            // blown its budget parked on the write gate and told the user it was asking for
            // a writing permission — so they read the wrong reason and, before the pause
            // reason was written down, granted more than they read.
            if (costMeter.budgetExceeded(run)) {
                park(run, step, String.format("bütçe aşıldı: %.4f USD / %.4f USD limit — devam için onay gerekiyor",
                        run.costUsd(), run.budgetUsd()), PauseReason.BUDGET);
                return;
            }

            if (policy.ask() && step.decision() != Decision.APPROVED) {
                // Fill the parameters in before asking. They used to be derived after the
                // approval, so the gate showed the planner's empty draft — live, a Slack
                // step waited on a human with no channel and no message text on screen.
                // Approving what you cannot read is not approval.
                ToolAgent.ParamRefresh refresh = toolAgent.refreshParams(run, step);
                costMeter.record(run, step, refresh.tokens(), refresh.costUsd());

                // And do not ask about something that cannot be sent. A raw
                // {{steps[0].result.issues.length}} used to reach the screen and be approved:
                // the call-time gate then refused it, so the human had spent their attention
                // on a sentence that was never going out. The one thing Relay asks of a
                // person is to read what will be sent — that has to be readable.
                String unreadable = toolAgent.unpresentable(step);
                if (unreadable != null) {
                    if (rewriteBeforeAsking(run, step, unreadable)) {
                        continue;
                    }
                    return;
                }

                park(run, step, "onay gerekiyor — " + policy.reason(), PauseReason.POLICY);
                return;
            }

            if (step.decision() == null) {
                step.decision(Decision.AUTO);
            }

            runStep(run, step);

            // Durdur was pressed while this step was in flight. The call finished and is on
            // the record; the run stops here rather than reporting the step's own outcome as
            // the reason it ended.
            if (stopIfCancelled(run)) {
                return;
            }

            if (step.status() == StepStatus.FAILED) {
                finish(run, RunStatus.FAILED);
                return;
            }
        }

        boolean anyFailed = run.steps().stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        // A rejection is not a failure — refusing a write is the product working. But a run
        // where *nothing* ran and every step was rejected (policy forbade it, or the user said
        // no) used to close as "Tamamlandı", because REJECTED is not FAILED. Nothing on the
        // screen contradicted it. Saying a run succeeded when it did no work is the one thing
        // this product cannot afford to get wrong, so it closes as failed and the trail says
        // which rule or which person stopped it.
        boolean nothingRan = run.steps().stream().noneMatch(s -> s.status() == StepStatus.DONE);
        boolean anyRejected = run.steps().stream().anyMatch(s -> s.status() == StepStatus.REJECTED);
        finish(run, anyFailed || (nothingRan && anyRejected) ? RunStatus.FAILED : RunStatus.DONE);
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
     * Sends a step back to its specialist because what it produced is not fit to be read.
     *
     * <p>The alternative was to show it anyway and let the call-time gate catch it, which is
     * what happened before: the person is asked about a template string, and whichever button
     * they press the answer is the same, because the message was never going to be sent. The
     * other alternative — failing the run at once — throws away a step the specialist very
     * often gets right on the second try, and the machinery for that try already exists.
     *
     * <p>Bounded by the same {@link Step#MAX_RETRIES} as every other retry, and the complaint
     * goes into {@code lastProviderError} so the next model turn is told what was wrong rather
     * than being asked the same question again.
     *
     * @return {@code true} when the step was sent back and the loop should carry on,
     *         {@code false} when the tries are used up and the run has been failed
     */
    private boolean rewriteBeforeAsking(Run run, Step step, String reason) {
        if (step.retriesExhausted()) {
            step.markFailed(reason, clock.now());
            journal.say(run, step.id(), AgentRole.COORDINATOR, AgentRole.USER,
                    "Adım " + step.ordinal() + " onayına sunulamadı: " + reason
                            + " Parametreler iki denemede de okunabilir hâle gelmedi.");
            publishStepFinished(run, step);
            finish(run, RunStatus.FAILED);
            return false;
        }
        step.lastProviderError(reason);
        step.sendBack();
        journal.say(run, step.id(), AgentRole.COORDINATOR, step.role(),
                "Parametreler onaya sunulabilir değil (" + step.attempts() + "/" + Step.MAX_RETRIES
                        + "): " + reason + " Onay istenmeden önce yeniden üretiliyor.");
        runs.save(run);
        publishCost(run);
        return true;
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
        // The identifiers this step was approved with have just been taken away from it, and
        // the lookup is going to hand it different ones. Keeping the approval would run the
        // write on parameters nobody saw — the exact thing retryWithProviderFeedback clears a
        // decision to prevent. The parameters cannot be refreshed here, because the step whose
        // result they come from has not run yet; the gate re-derives them on the way back.
        boolean write = policyEngine.evaluate(step.toolName()).ask();
        if (write) {
            step.decision(null);
        }

        journal.say(run, step.id(), AgentRole.COORDINATOR, AgentRole.USER,
                "Adım " + step.ordinal() + " kaydı adıyla değil varsayımla hedefliyordu."
                        + " Plana önce " + lookupTool + " adımı eklendi."
                        + (write ? " Kayıt bulunduktan sonra parametreler yeniden üretilip"
                                 + " tekrar onayına gelecek." : ""));
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

    /**
     * Stops in front of a human and says which question is being asked.
     *
     * <p>{@code cause} is written onto the step rather than only into the sentence, because
     * the answer arrives in a different request: {@code RunService.approve} has to be able to
     * tell "the user lifted the budget" from "the user signed off this write", and a Turkish
     * sentence is not something to branch on.
     */
    private void park(Run run, Step step, String reason, PauseReason cause) {
        step.markAwaitingApproval(cause);
        run.status(RunStatus.AWAITING_APPROVAL);
        String from = cause == PauseReason.BUDGET ? AgentRole.COST : AgentRole.COORDINATOR;
        journal.say(run, step.id(), from, AgentRole.USER, "Adım " + step.ordinal() + " — " + reason);
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.id().toString());
        data.put("ordinal", step.ordinal());
        data.put("title", step.title());
        data.put("toolName", step.toolName());
        data.put("params", step.params());
        data.put("reason", reason);
        data.put("pausedBy", cause.wire());
        events.publish(run.id(), RunEvent.of(RunEvent.STEP_AWAITING, data));
    }

    private void rejectStep(Run run, Step step, String reason) {
        step.reject(reason);
        step.finishedAt(clock.now());
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                "Adım reddedildi: " + reason);
        publishStepFinished(run, step);
    }

    /** @return {@code true} when the run was cancelled and the caller must stop walking it. */
    private boolean stopIfCancelled(Run run) {
        if (!cancelRequests.containsKey(run.id())) {
            return false;
        }
        stop(run);
        return true;
    }

    /**
     * Closes a cancelled run: everything that had not finished is written off as rejected,
     * so no step is left claiming it is still about to happen.
     *
     * <p>Only the caller holding the run's lock may call this — it writes the aggregate.
     */
    private void stop(Run run) {
        String actor = by(cancelRequests.get(run.id()));
        for (Step step : run.steps()) {
            if (!step.status().terminal()) {
                step.reject("akış iptal edildi" + actor);
                step.finishedAt(clock.now());
                publishStepFinished(run, step);
            }
        }
        journal.say(run, null, AgentRole.USER, AgentRole.COORDINATOR,
                "Akış iptal edildi" + actor + " — kalan adımlar çalıştırılmadı.");
        // No closing summary: the user just asked Relay to stop spending on this run, and a
        // summary is one more model call to say what the trail already says.
        finish(run, RunStatus.CANCELLED, false);
    }

    /** " (ayse@sirket.com)" — or nothing at all when the caller did not say who. */
    private static String by(String actor) {
        return actor == null || actor.isBlank() ? "" : " (" + actor.trim() + ")";
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
        finish(run, status, true);
    }

    private void finish(Run run, RunStatus status, boolean summarise) {
        cancelRequests.remove(run.id());
        run.status(status);
        run.finishedAt(clock.now());
        if (summarise) {
            sayWhatHappened(run, status);
        }
        journal.say(run, null, AgentRole.COORDINATOR, AgentRole.USER,
                "Akış bitti: " + status.wire() + String.format(" · %,d token · $%.4f", run.costTokens(), run.costUsd()));
        runs.save(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.wire());
        data.put("tokens", run.costTokens());
        data.put("costUsd", run.costUsd());
        // When it ended, from the clock that ended it. The screen used to stamp its own,
        // which meant a replay of this frame moved the finish time to the reconnect.
        data.put("finishedAt", Views.iso(run.finishedAt()));
        events.publish(run.id(), RunEvent.of(RunEvent.RUN_FINISHED, data));
        // Say the ending out loud to the transport as well. run.finished was the last frame
        // a client would ever get, but nothing hung up: the stream idled for half an hour,
        // timed out, and the browser — which cannot tell a timeout from a dropped line —
        // reconnected and replayed the whole run onto a screen that was already done.
        events.closed(run.id());
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
