package com.relay.application.orchestrator;

import com.relay.application.port.Clock;
import com.relay.application.port.RunRepository;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Decision;
import com.relay.domain.PauseReason;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * What the card the user pressed was <em>about</em> — the few fields worth paying tokens
     * for, and nothing else.
     *
     * <p>Every field is optional and every one of them is copied into the goal sentence, so
     * this is a budget as much as a shape: the mail's whole body does not belong here (the
     * flow reads it in a step of its own), a subject line, a sender and one sentence do.
     *
     * @param itemId the brief item id — {@code gmail:18f2…}, {@code jira:KAN-42},
     *               {@code github-pr:acme/pay#12}. The handle in it is how a record gets
     *               named in the goal, and how a mail reply finds the message to read.
     */
    public record SuggestionContext(String itemId, String source, String title, String from,
                                    String summary, String url) {

        boolean empty() {
            return blank(itemId) && blank(source) && blank(title) && blank(from)
                    && blank(summary) && blank(url);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /** A client that says nothing about the card gets exactly the behaviour it had before. */
    public Run startFromSuggestion(String toolName, Map<String, Object> params, String label,
                                   Double budgetUsd) {
        return startFromSuggestion(toolName, params, label, null, budgetUsd);
    }

    /**
     * A card on the Bugün screen turned into a real run (BRIEF §3).
     *
     * <p>The step is seeded instead of planned — the planner has nothing to decide, the user
     * already chose. Everything after that is identical to a typed goal: the coordinator
     * walks it, the policy engine sees the same WRITE risk and the same approval gate opens.
     * There is no fast path around the gate.
     *
     * <p>What the flow knows about the item is the whole of {@code context}. Without it the
     * goal was the button's own label, so a live run went out as "Cevap yaz" and the draft it
     * produced was titled {@code Re: Cevap} — the specialist had the label and nothing else to
     * write a subject from. The label alone also left every record key ungrounded: a
     * {@code jira.addComment} on KAN-42 whose goal never says KAN-42 is, by Relay's own rule,
     * a key that came from nowhere.
     */
    public Run startFromSuggestion(String toolName, Map<String, Object> params, String label,
                                   SuggestionContext context, Double budgetUsd) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool is required");
        }
        if (tools != null && tools.find(toolName).isEmpty()) {
            throw new IllegalArgumentException("unknown tool: " + toolName);
        }
        String title = SuggestionGoal.stepTitle(label, toolName);
        String goal = SuggestionGoal.of(label, toolName, context);
        List<SeedStep> seeds = seedSteps(toolName, params, context, title);

        Run run = Run.create(goal, clock.now(), budgetUsd != null ? budgetUsd : defaultBudgetUsd);
        int ordinal = 0;
        for (SeedStep seed : seeds) {
            run.addStep(Step.create(run.id(), ++ordinal, seed.title(),
                    AgentRole.toolAgent(seed.toolName()), seed.toolName(), seed.params()));
        }
        runs.save(run);
        journal.say(run, null, AgentRole.USER, AgentRole.COORDINATOR,
                seeds.size() == 1
                        ? "Bugün ekranından öneri çalıştırılıyor: " + toolName
                        : "Bugün ekranından öneri çalıştırılıyor: " + toolName
                                + " — cevap yazılmadan önce mail " + READ_MAIL + " ile okunuyor.");
        executor.execute(() -> coordinator.drive(run.id()));
        return run;
    }

    /** The read that has to happen before a reply can be written. */
    private static final String READ_MAIL = "gmail.getMessage";

    /**
     * The fields of a reply that are the reply — and therefore may not be pre-written.
     *
     * <p>The suggestion arrives with a courtesy template ("… konusunu aldım, bugün içinde
     * dönüş yapacağım"), which is schema-valid, so the specialist would never be asked to
     * write anything and the answer would be about the subject line rather than about the
     * mail. Dropped, they have to be derived — and the only place to derive them from is the
     * message the step in front just read.
     */
    private static final Set<String> WRITTEN_FROM_THE_MAIL = Set.of("subject", "body");

    /**
     * The steps a suggestion turns into: one, or two when the answer has to be read first.
     *
     * <p>A mail reply is the case where a single step cannot be honest. Everything the flow
     * knew about the message was its subject, and a subject is not enough to answer with —
     * live, that produced a draft addressed to a conversation whose content Relay had never
     * seen. So {@code gmail.getMessage} goes in front: the sender, the real subject and the
     * body land in the previous results, and the draft is written from them.
     *
     * <p>Only for mail. A Jira comment or a GitHub review does not need the record's full
     * text to say "starting on this" — there the goal sentence carries enough, and a second
     * provider call would be spending someone's budget to confirm what the card already said.
     */
    private List<SeedStep> seedSteps(String toolName, Map<String, Object> params,
                                     SuggestionContext context, String title) {
        Map<String, Object> given = params == null ? Map.of() : params;
        String messageId = mailBehind(toolName, given, context);
        if (messageId == null) {
            return List.of(new SeedStep(title, toolName, given));
        }
        return List.of(
                new SeedStep("Cevaplanacak maili oku", READ_MAIL, Map.of("messageId", messageId)),
                new SeedStep(title, toolName, without(given, WRITTEN_FROM_THE_MAIL)));
    }

    /**
     * The message a draft suggestion is answering, or {@code null} when this is not one.
     *
     * <p>The tool is recognised by what it does rather than by a hard-coded name, the same
     * way the brief finds it: a Gmail tool that writes drafts. The id comes from the card's
     * own item id ({@code gmail:18f2…}), which is where the brief got it from in the first
     * place — and it only ever feeds a READ, so a wrong one costs a failed lookup, not a
     * write to a stranger's conversation.
     */
    private String mailBehind(String toolName, Map<String, Object> params, SuggestionContext context) {
        String name = toolName.toLowerCase(Locale.ROOT);
        if (!name.startsWith("gmail.") || !name.contains("draft")) {
            return null;
        }
        if (tools == null || tools.find(READ_MAIL).isEmpty()) {
            return null;
        }
        Object given = params.get("messageId");
        if (given != null && !String.valueOf(given).isBlank()) {
            return String.valueOf(given).trim();
        }
        String itemId = context == null || context.itemId() == null ? "" : context.itemId().trim();
        String prefix = "gmail:";
        return itemId.startsWith(prefix) && itemId.length() > prefix.length()
                ? itemId.substring(prefix.length()) : null;
    }

    private static Map<String, Object> without(Map<String, Object> params, Set<String> fields) {
        Map<String, Object> kept = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!fields.contains(key.toLowerCase(Locale.ROOT))) {
                kept.put(key, value);
            }
        });
        return kept;
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

    /** " (ayse@sirket.com)" — or nothing at all when the caller did not say. */
    private static String by(String actor) {
        return actor == null || actor.isBlank() ? "" : " (" + actor.trim() + ")";
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
        return approve(runId, stepId, null, null);
    }

    public Run approve(UUID runId, UUID stepId, String actor) {
        return approve(runId, stepId, null, actor);
    }

    /**
     * Approves a step, optionally with the parameters the human corrected on the screen.
     *
     * <p>Before this, the gate was binary and a nearly-right parameter cost a rejection, a
     * written reason and another model turn — while the person pressing the button already
     * knew the right value. Now they can type it. What they type is checked against the
     * tool's own schema first: a refused edit changes nothing at all and the step stays at
     * the gate, which is the difference between an approval gate and an open door.
     *
     * @param editedParams the fields the user rewrote, or {@code null} to approve what is on
     *                     screen — approving unchanged behaves exactly as it always did
     * @param actor        who pressed the button — written into the audit trail. The trail
     *                     claimed "kim, neyi, neden" but only ever recorded a generic user,
     *                     so on a shared workspace nobody could answer the "kim".
     */
    public Run approve(UUID runId, UUID stepId, Map<String, Object> editedParams, String actor) {
        // Under the coordinator's lock, so that two people pressing the same button produce
        // one decision. Both requests used to read a step that was awaiting approval and both
        // wrote "Onaylandı" into the trail, while the tool ran once — a second signature
        // against an action that had already happened.
        return coordinator.decide(runId, () -> approveNow(runId, stepId, editedParams, actor));
    }

    private Run approveNow(UUID runId, UUID stepId, Map<String, Object> editedParams, String actor) {
        Run run = get(runId);
        stillOpen(run);
        Step step = step(run, stepId);
        if (step.status() != StepStatus.AWAITING_APPROVAL) {
            // 409 rather than 400, for the same reason a decision on a finished run is: the
            // button was legal when the screen drew it, and somebody else got there first.
            throw new Conflict("step " + stepId + " is not awaiting approval; it is already "
                    + step.status().wire());
        }
        if (editedParams != null && !editedParams.isEmpty()) {
            applyEdit(run, step, editedParams, actor);
        }
        // What the button does depends on which question the step asked. It used to depend on
        // whether the run happened to be over budget, which is not the same thing: a run that
        // drifted over the ceiling while a write sat at the gate turned that write's approval
        // into "spend without a limit from here on", and nothing on screen said so.
        boolean money = step.pausedBy() == PauseReason.BUDGET;
        if (money) {
            run.budgetOverridden(true);
            step.resumeAfterBudget();
        } else {
            step.approve();
        }
        run.status(RunStatus.RUNNING);
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                money
                        ? "Bütçe tavanı bu akış için kaldırıldı" + by(actor) + " — devam et."
                        : "Onaylandı" + by(actor) + " — devam et.");
        runs.save(run);
        executor.execute(() -> coordinator.drive(runId));
        return run;
    }

    /**
     * Writes the human's correction onto the step — or refuses the whole approval.
     *
     * <p>Every changed field lands on the timeline with both values and the name of whoever
     * typed them: "kim, neyi, neden" is the promise the trail makes, and an edit that only
     * showed its result would answer none of the three.
     */
    private void applyEdit(Run run, Step step, Map<String, Object> editedParams, String actor) {
        if (tools == null || step.toolName() == null || step.toolName().isBlank()) {
            throw new IllegalArgumentException("step " + step.id() + " has no tool to take parameters");
        }
        Tool tool = tools.find(step.toolName())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + step.toolName()));

        ParamEdit.Result edit = ParamEdit.of(tool, step.params(), editedParams);
        if (!edit.ok()) {
            throw new InvalidParams("Düzenlenen parametreler " + tool.name()
                    + " şemasına uymuyor — adım onayda kaldı.", edit.errors());
        }
        if (edit.changes().isEmpty()) {
            return;
        }
        step.paramsEditedByUser(edit.params());
        String agent = step.role() == null ? AgentRole.COORDINATOR : step.role();
        edit.changes().forEach((field, change) -> journal.say(run, step.id(), AgentRole.USER, agent,
                "Parametre kullanıcı tarafından düzenlendi" + by(actor) + " — " + field + ": "
                        + show(change.before()) + " → " + show(change.after())));
    }

    /** Long values are for the parameter panel; the trail needs the value to be recognisable. */
    private static String show(Object value) {
        if (value == null) {
            return "(boş)";
        }
        String text = String.valueOf(value);
        return "\"" + (text.length() > 120 ? text.substring(0, 119) + "…" : text) + "\"";
    }

    public Run reject(UUID runId, UUID stepId, String reason) {
        return reject(runId, stepId, reason, null);
    }

    public Run reject(UUID runId, UUID stepId, String reason, String actor) {
        return coordinator.decide(runId, () -> rejectNow(runId, stepId, reason, actor));
    }

    private Run rejectNow(UUID runId, UUID stepId, String reason, String actor) {
        Run run = get(runId);
        stillOpen(run);
        Step step = step(run, stepId);
        if (step.status().terminal()) {
            throw new Conflict("step " + stepId + " already finished as " + step.status().wire());
        }
        if (step.decision() == Decision.REJECTED) {
            // The coordinator turns a rejection into a terminal step on its next pass, so
            // between the press and that pass the step is still PENDING and would take a
            // second "Reddedildi" line for a decision already made.
            throw new Conflict("step " + stepId + " has already been rejected");
        }
        String why = reason == null || reason.isBlank() ? "kullanıcı reddetti" : reason.trim();
        step.decision(Decision.REJECTED);
        step.rejectReason(why);
        // Left PENDING on purpose: the coordinator turns the rejection into a terminal
        // REJECTED step, records it on the timeline and tells the agent why.
        step.status(StepStatus.PENDING);
        run.status(RunStatus.RUNNING);
        journal.say(run, step.id(), AgentRole.USER, step.role() == null ? AgentRole.COORDINATOR : step.role(),
                "Reddedildi" + by(actor) + ": " + why);
        runs.save(run);
        executor.execute(() -> coordinator.drive(runId));
        return run;
    }

    /**
     * Stops a run the user no longer wants — the whole flow, not one step at a time.
     *
     * <p>Returns the run as it stands once the request has been placed. A run that was
     * waiting on a human is already {@code cancelled} here; a run that is mid tool call is
     * still {@code running}, because the call in flight is allowed to finish (see
     * {@link Coordinator#cancel}). The caller learns which of the two happened from the
     * status it gets back, and the {@code run.finished} event closes the screen either way.
     *
     * @param actor who pressed Durdur — written into the trail, same as approve/reject
     */
    public Run cancel(UUID runId, String actor) {
        Run run = get(runId);
        if (run.status().terminal()) {
            throw new Conflict("run " + runId + " already finished as " + run.status().wire());
        }
        coordinator.cancel(runId, actor);
        return get(runId);
    }

    /**
     * A finished run takes no more decisions.
     *
     * <p>409 rather than 400: the request was well formed and was legal when the screen drew
     * the button — the run moved on underneath it. That is exactly the case of someone
     * approving a step on a run a colleague has just cancelled.
     */
    private void stillOpen(Run run) {
        if (run.status().terminal()) {
            throw new Conflict("run " + run.id() + " is already " + run.status().wire()
                    + "; no step can be approved or rejected");
        }
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

    /** The run exists, the request is fine — its state has moved on. Answered with 409. */
    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    /**
     * The parameters a human typed at the gate did not survive the tool's schema.
     *
     * <p>Carries a sentence per field rather than one summary line: the screen puts each one
     * under the box that caused it, and "channel şu değerlerden biri olmalı" three fields
     * away from the channel box is not an error message, it is a puzzle. Answered with 400.
     */
    public static class InvalidParams extends RuntimeException {
        private final Map<String, String> fields;

        public InvalidParams(String message, Map<String, String> fields) {
            super(message);
            this.fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        public Map<String, String> fields() {
            return fields;
        }
    }
}
