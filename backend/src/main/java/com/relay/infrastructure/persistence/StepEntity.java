package com.relay.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "steps")
public class StepEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RunEntity run;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "agent_role", length = 64)
    private String agentRole;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 16)
    private String decision;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    private long tokens;

    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    /** Provider-qualified id of the model that did most of this step's tokens — see {@code Step.model}. */
    @Column(length = 128)
    private String model;

    /** How many tokens that model did, so the comparison survives the round trip. */
    @Column(name = "model_tokens", nullable = false)
    private long modelTokens;

    /** Nullable on purpose: null is "not derivable", not zero — see {@code Step.premiumCostUsd}. */
    @Column(name = "premium_cost_usd")
    private Double premiumCostUsd;

    @Column(nullable = false)
    private int attempts;

    /** Set when a human rewrote the parameters at the gate — see {@code Step.paramsLocked}. */
    @Column(name = "params_locked", nullable = false)
    private boolean paramsLocked;

    /** "policy" / "budget" while the step waits on a human — see {@code Step.pausedBy}. */
    @Column(name = "paused_by", length = 16)
    private String pausedBy;

    /** The plan-coverage warning shown at the gate — see {@code Step.warning}. */
    @Column(columnDefinition = "text")
    private String warning;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public RunEntity getRun() {
        return run;
    }

    public void setRun(RunEntity run) {
        this.run = run;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getTokens() {
        return tokens;
    }

    public void setTokens(long tokens) {
        this.tokens = tokens;
    }

    public double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(double costUsd) {
        this.costUsd = costUsd;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getModelTokens() {
        return modelTokens;
    }

    public void setModelTokens(long modelTokens) {
        this.modelTokens = modelTokens;
    }

    public Double getPremiumCostUsd() {
        return premiumCostUsd;
    }

    public void setPremiumCostUsd(Double premiumCostUsd) {
        this.premiumCostUsd = premiumCostUsd;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public boolean isParamsLocked() {
        return paramsLocked;
    }

    public void setParamsLocked(boolean paramsLocked) {
        this.paramsLocked = paramsLocked;
    }

    public String getPausedBy() {
        return pausedBy;
    }

    public void setPausedBy(String pausedBy) {
        this.pausedBy = pausedBy;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }
}
