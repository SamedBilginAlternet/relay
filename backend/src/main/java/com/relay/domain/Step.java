package com.relay.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One unit of work inside a run. Pure Java: no framework, no annotations.
 */
public class Step {

    /** Verifier may send a step back at most this many times. */
    public static final int MAX_RETRIES = 2;

    private final UUID id;
    private final UUID runId;
    private int ordinal;
    private String title;
    private String role;
    private String toolName;
    private Map<String, Object> params = new LinkedHashMap<>();
    private StepStatus status = StepStatus.PENDING;
    private Decision decision;
    private String rejectReason;
    private Object result;
    private String error;
    /**
     * The provider's own rejection from the previous attempt. Survives {@link #markRunning}
     * — unlike {@link #error} — because the specialist needs it while producing the next
     * set of parameters.
     */
    private String lastProviderError;
    /**
     * A human typed these parameters at the approval gate.
     *
     * <p>Everything that would otherwise rewrite them — the specialist's model turn, the
     * address correction — stands down while this is set, because a value the user saw,
     * changed and approved must reach the provider as it was on the screen. Persisted:
     * approval and execution are two different requests, and between them the run is read
     * back from the database.
     */
    private boolean paramsLocked;
    /**
     * Why this step is waiting on a human — {@code null} unless it is.
     *
     * <p>Persisted for the same reason {@link #paramsLocked} is: the approval is a second
     * request and the run is read back from the database in between, so the coordinator's
     * knowledge of "this was the money gate, not the write gate" has to survive the round
     * trip. Without it, approving anything at all while the run happened to be over budget
     * lifted the ceiling.
     */
    private PauseReason pausedBy;
    private Instant startedAt;
    private Instant finishedAt;
    private long tokens;
    private double costUsd;
    /**
     * Which model answered for this step, provider-qualified — {@code groq:llama-3.1-8b-instant}.
     *
     * <p>A step is rarely one call: parameters are derived, the provider may refuse them and
     * they are derived again, then the verifier reads the result — and since the tier is
     * chosen per job, those calls do not all land on the same model. Listing them all would
     * put a second history next to the one the trail already keeps, so the step keeps a
     * single name: <b>the model of the call that did the most tokens</b>. That is the call
     * that shaped the step and the one whose price dominates its cost, so it is the honest
     * answer to "what answered here". {@link #modelTokens} is the high-water mark it is
     * compared against.
     */
    private String model;
    /**
     * Tokens done by the call that {@link #model} names. Persisted, because a step is written
     * at the approval gate and read back when the approval arrives, and a comparison that
     * forgets its incumbent hands the field to whichever call happens to come after the
     * round trip.
     */
    private long modelTokens;
    /**
     * What this step's tokens would have cost had every one of its calls used the strong
     * model's price. Same token counts, the other price list — arithmetic, not an estimate.
     *
     * <p>{@code null} when at least one call could not be priced that way: the offline stub
     * counts characters rather than tokens and no provider ever billed them. A total that is
     * missing one of its calls is not a total, and a number the product cannot stand behind
     * is worse than no number, because the whole point of this field is that a judge can
     * recompute it.
     */
    private Double premiumCostUsd;
    /** Latched by the first unpriceable call; only cleared by resetting the field outright. */
    private boolean premiumUnknown;
    private int attempts;

    public Step(UUID id, UUID runId, int ordinal, String title, String role, String toolName,
                Map<String, Object> params) {
        this.id = id;
        this.runId = runId;
        this.ordinal = ordinal;
        this.title = title;
        this.role = role;
        this.toolName = toolName;
        if (params != null) {
            this.params = new LinkedHashMap<>(params);
        }
    }

    public static Step create(UUID runId, int ordinal, String title, String role, String toolName,
                              Map<String, Object> params) {
        return new Step(UUID.randomUUID(), runId, ordinal, title, role, toolName, params);
    }

    // ---- transitions ------------------------------------------------------

    public void markRunning(Instant now) {
        this.status = StepStatus.RUNNING;
        this.startedAt = now;
        this.error = null;
    }

    public void markAwaitingApproval(PauseReason reason) {
        this.status = StepStatus.AWAITING_APPROVAL;
        this.pausedBy = reason;
    }

    public void approve() {
        this.decision = Decision.APPROVED;
        this.rejectReason = null;
        this.status = StepStatus.PENDING;
        this.pausedBy = null;
    }

    /**
     * The human lifted the run's budget ceiling — and lifted nothing else.
     *
     * <p>Deliberately not {@link #approve()}: the step goes back in the queue exactly as it
     * was, undecided. A write that was only ever stopped by the money gate still has to be
     * read and approved on its own, otherwise "devam et" on a cost dialog would buy a Slack
     * message nobody looked at.
     */
    public void resumeAfterBudget() {
        this.status = StepStatus.PENDING;
        this.pausedBy = null;
    }

    public void reject(String reason) {
        this.decision = Decision.REJECTED;
        this.rejectReason = reason;
        this.status = StepStatus.REJECTED;
    }

    public void markDone(Object result, Instant now) {
        this.status = StepStatus.DONE;
        this.result = result;
        this.finishedAt = now;
        this.lastProviderError = null;
    }

    public void markFailed(String error, Instant now) {
        this.status = StepStatus.FAILED;
        this.error = error;
        this.finishedAt = now;
    }

    /** Verifier sent the step back: reset it so the coordinator retries. */
    public void sendBack() {
        this.attempts++;
        this.status = StepStatus.PENDING;
        this.result = null;
        this.finishedAt = null;
    }

    public boolean retriesExhausted() {
        return attempts >= MAX_RETRIES;
    }

    /** Usage from a call whose model and premium price are not known. */
    public void addCost(long tokens, double costUsd) {
        addCost(tokens, costUsd, null, null);
    }

    /**
     * @param premiumCostUsd the same tokens at the strong model's price, or {@code null} when
     *                       the call cannot be priced that way — which makes the step's whole
     *                       premium figure unknown rather than merely smaller
     * @param model          provider-qualified id of whatever answered, or {@code null}
     */
    public void addCost(long tokens, double costUsd, Double premiumCostUsd, String model) {
        this.tokens += tokens;
        this.costUsd += costUsd;
        if (premiumCostUsd == null) {
            this.premiumUnknown = true;
            this.premiumCostUsd = null;
        } else if (!premiumUnknown) {
            this.premiumCostUsd = (this.premiumCostUsd == null ? 0d : this.premiumCostUsd) + premiumCostUsd;
        }
        if (model != null && (this.model == null || tokens > modelTokens)) {
            this.model = model;
            this.modelTokens = tokens;
        }
    }

    // ---- accessors --------------------------------------------------------

    public UUID id() {
        return id;
    }

    public UUID runId() {
        return runId;
    }

    public int ordinal() {
        return ordinal;
    }

    /** Renumbering only: the coordinator may insert a step into a running plan. */
    public void ordinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public String title() {
        return title;
    }

    public void title(String title) {
        this.title = title;
    }

    public String role() {
        return role;
    }

    public void role(String role) {
        this.role = role;
    }

    public String toolName() {
        return toolName;
    }

    public void toolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> params() {
        return params;
    }

    public void params(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }

    /** The human corrected the parameters at the gate: these are final, nobody rewrites them. */
    public void paramsEditedByUser(Map<String, Object> params) {
        params(params);
        this.paramsLocked = true;
    }

    public boolean paramsLocked() {
        return paramsLocked;
    }

    public void paramsLocked(boolean paramsLocked) {
        this.paramsLocked = paramsLocked;
    }

    /** Why the step is parked, or {@code null} when it is not. */
    public PauseReason pausedBy() {
        return pausedBy;
    }

    public void pausedBy(PauseReason pausedBy) {
        this.pausedBy = pausedBy;
    }

    public StepStatus status() {
        return status;
    }

    public void status(StepStatus status) {
        this.status = status;
    }

    public Decision decision() {
        return decision;
    }

    public void decision(Decision decision) {
        this.decision = decision;
    }

    public String rejectReason() {
        return rejectReason;
    }

    public void rejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Object result() {
        return result;
    }

    public void result(Object result) {
        this.result = result;
    }

    public String lastProviderError() {
        return lastProviderError;
    }

    public void lastProviderError(String lastProviderError) {
        this.lastProviderError = lastProviderError;
    }

    public String error() {
        return error;
    }

    public void error(String error) {
        this.error = error;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void startedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public void finishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long tokens() {
        return tokens;
    }

    public void tokens(long tokens) {
        this.tokens = tokens;
    }

    public double costUsd() {
        return costUsd;
    }

    public void costUsd(double costUsd) {
        this.costUsd = costUsd;
    }

    /** The model that did the most of this step's tokens, or {@code null} if none answered. */
    public String model() {
        return model;
    }

    public long modelTokens() {
        return modelTokens;
    }

    /** Reading a step back from the database, and nothing else. */
    public void model(String model, long modelTokens) {
        this.model = model;
        this.modelTokens = modelTokens;
    }

    /** {@code null} means "cannot be derived honestly", never zero. */
    public Double premiumCostUsd() {
        return premiumCostUsd;
    }

    public void premiumCostUsd(Double premiumCostUsd) {
        this.premiumCostUsd = premiumCostUsd;
        // A step read back with no premium but with tokens on it spent something nobody could
        // price; the next call on it must not turn that gap into a total that looks complete.
        this.premiumUnknown = premiumCostUsd == null && tokens > 0;
    }

    public int attempts() {
        return attempts;
    }

    public void attempts(int attempts) {
        this.attempts = attempts;
    }
}
