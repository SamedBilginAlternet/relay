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

    public void addCost(long tokens, double costUsd) {
        this.tokens += tokens;
        this.costUsd += costUsd;
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

    public int attempts() {
        return attempts;
    }

    public void attempts(int attempts) {
        this.attempts = attempts;
    }
}
