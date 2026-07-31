package com.relay.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "runs")
public class RunEntity {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cost_tokens", nullable = false)
    private long costTokens;

    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    @Column(name = "budget_usd")
    private Double budgetUsd;

    @Column(name = "budget_overridden", nullable = false)
    private boolean budgetOverridden;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal asc")
    private List<StepEntity> steps = new ArrayList<>();

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt asc")
    private List<AgentMessageEntity> messages = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getCostTokens() {
        return costTokens;
    }

    public void setCostTokens(long costTokens) {
        this.costTokens = costTokens;
    }

    public double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(double costUsd) {
        this.costUsd = costUsd;
    }

    public Double getBudgetUsd() {
        return budgetUsd;
    }

    public void setBudgetUsd(Double budgetUsd) {
        this.budgetUsd = budgetUsd;
    }

    public boolean isBudgetOverridden() {
        return budgetOverridden;
    }

    public void setBudgetOverridden(boolean budgetOverridden) {
        this.budgetOverridden = budgetOverridden;
    }

    public List<StepEntity> getSteps() {
        return steps;
    }

    public List<AgentMessageEntity> getMessages() {
        return messages;
    }
}
