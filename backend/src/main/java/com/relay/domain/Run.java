package com.relay.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A delegated piece of work: goal + ordered steps + the agent chatter around them.
 * Pure Java: no framework, no annotations.
 */
public class Run {

    private final UUID id;
    private final String goal;
    private RunStatus status = RunStatus.PLANNING;
    private final Instant createdAt;
    private Instant finishedAt;
    private long costTokens;
    private double costUsd;
    private Double budgetUsd;
    private boolean budgetOverridden;
    private final List<Step> steps = new ArrayList<>();
    private final List<AgentMessage> messages = new ArrayList<>();

    public Run(UUID id, String goal, Instant createdAt, Double budgetUsd) {
        this.id = id;
        this.goal = goal;
        this.createdAt = createdAt;
        this.budgetUsd = budgetUsd;
    }

    public static Run create(String goal, Instant now, Double budgetUsd) {
        return new Run(UUID.randomUUID(), goal, now, budgetUsd);
    }

    // ---- steps / messages -------------------------------------------------

    public void addStep(Step step) {
        steps.add(step);
        steps.sort(Comparator.comparingInt(Step::ordinal));
    }

    public void replaceSteps(List<Step> newSteps) {
        steps.clear();
        steps.addAll(newSteps);
        steps.sort(Comparator.comparingInt(Step::ordinal));
    }

    public List<Step> steps() {
        return steps;
    }

    public Optional<Step> step(UUID stepId) {
        return steps.stream().filter(s -> s.id().equals(stepId)).findFirst();
    }

    /** First step that still needs work, in ordinal order. */
    public Optional<Step> nextActionable() {
        return steps.stream().filter(s -> !s.status().terminal()).findFirst();
    }

    public void addMessage(AgentMessage message) {
        messages.add(message);
    }

    public List<AgentMessage> messages() {
        return messages;
    }

    // ---- cost -------------------------------------------------------------

    public void addCost(long tokens, double usd) {
        this.costTokens += tokens;
        this.costUsd += usd;
    }

    public boolean overBudget() {
        return budgetUsd != null && costUsd > budgetUsd;
    }

    // ---- accessors --------------------------------------------------------

    public UUID id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public RunStatus status() {
        return status;
    }

    public void status(RunStatus status) {
        this.status = status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public void finishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long costTokens() {
        return costTokens;
    }

    public void costTokens(long costTokens) {
        this.costTokens = costTokens;
    }

    public double costUsd() {
        return costUsd;
    }

    public void costUsd(double costUsd) {
        this.costUsd = costUsd;
    }

    public Double budgetUsd() {
        return budgetUsd;
    }

    public void budgetUsd(Double budgetUsd) {
        this.budgetUsd = budgetUsd;
    }

    public boolean budgetOverridden() {
        return budgetOverridden;
    }

    public void budgetOverridden(boolean budgetOverridden) {
        this.budgetOverridden = budgetOverridden;
    }
}
