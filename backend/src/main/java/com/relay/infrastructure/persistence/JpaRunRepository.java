package com.relay.infrastructure.persistence;

import com.relay.application.port.RunRepository;
import com.relay.domain.AgentMessage;
import com.relay.domain.Decision;
import com.relay.domain.PauseReason;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Maps the Run aggregate to JPA and back. The only place that knows both worlds. */
@Repository
public class JpaRunRepository implements RunRepository {

    private final RunEntityRepository runs;

    public JpaRunRepository(RunEntityRepository runs) {
        this.runs = runs;
    }

    @Override
    @Transactional
    public Run save(Run run) {
        RunEntity entity = runs.findById(run.id()).orElseGet(() -> {
            RunEntity fresh = new RunEntity();
            fresh.setId(run.id());
            fresh.setGoal(run.goal());
            fresh.setCreatedAt(run.createdAt());
            return fresh;
        });
        entity.setStatus(run.status().wire());
        entity.setFinishedAt(run.finishedAt());
        entity.setCostTokens(run.costTokens());
        entity.setCostUsd(run.costUsd());
        entity.setBudgetUsd(run.budgetUsd());
        entity.setBudgetOverridden(run.budgetOverridden());

        Map<UUID, StepEntity> existingSteps = new HashMap<>();
        entity.getSteps().forEach(s -> existingSteps.put(s.getId(), s));
        Set<UUID> keptSteps = new HashSet<>();
        for (Step step : run.steps()) {
            StepEntity stepEntity = existingSteps.get(step.id());
            if (stepEntity == null) {
                stepEntity = new StepEntity();
                stepEntity.setId(step.id());
                stepEntity.setRun(entity);
                entity.getSteps().add(stepEntity);
            }
            keptSteps.add(step.id());
            stepEntity.setOrdinal(step.ordinal());
            stepEntity.setTitle(step.title());
            stepEntity.setAgentRole(step.role());
            stepEntity.setToolName(step.toolName());
            stepEntity.setParams(new LinkedHashMap<>(step.params()));
            stepEntity.setStatus(step.status().wire());
            stepEntity.setDecision(step.decision() == null ? null : step.decision().wire());
            stepEntity.setRejectReason(step.rejectReason());
            stepEntity.setResult(asMap(step.result()));
            stepEntity.setError(step.error());
            stepEntity.setStartedAt(step.startedAt());
            stepEntity.setFinishedAt(step.finishedAt());
            stepEntity.setTokens(step.tokens());
            stepEntity.setCostUsd(step.costUsd());
            stepEntity.setAttempts(step.attempts());
            stepEntity.setParamsLocked(step.paramsLocked());
            stepEntity.setPausedBy(step.pausedBy() == null ? null : step.pausedBy().wire());
        }
        entity.getSteps().removeIf(s -> !keptSteps.contains(s.getId()));

        Set<UUID> existingMessages = new HashSet<>();
        entity.getMessages().forEach(m -> existingMessages.add(m.getId()));
        for (AgentMessage message : run.messages()) {
            if (existingMessages.contains(message.id())) {
                continue;
            }
            AgentMessageEntity messageEntity = new AgentMessageEntity();
            messageEntity.setId(message.id());
            messageEntity.setRun(entity);
            messageEntity.setStepId(message.stepId());
            messageEntity.setFromAgent(message.fromAgent());
            messageEntity.setToAgent(message.toAgent());
            messageEntity.setContent(message.content());
            messageEntity.setCreatedAt(message.createdAt());
            entity.getMessages().add(messageEntity);
        }

        runs.save(entity);
        return run;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Run> findById(UUID id) {
        return runs.findById(id).map(JpaRunRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Run> findAll(int page, int size) {
        return runs.findAll(PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(JpaRunRepository::toDomain)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Run> findByStatus(com.relay.domain.RunStatus status, int page, int size) {
        return runs.findByStatus(status.wire(),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                                Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(JpaRunRepository::toDomain)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return runs.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(com.relay.domain.RunStatus status) {
        return runs.countByStatus(status.wire());
    }

    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", result);
        return wrapped;
    }

    static Run toDomain(RunEntity entity) {
        Run run = new Run(entity.getId(), entity.getGoal(), entity.getCreatedAt(), entity.getBudgetUsd());
        run.status(RunStatus.valueOf(entity.getStatus().toUpperCase(Locale.ROOT)));
        run.finishedAt(entity.getFinishedAt());
        run.costTokens(entity.getCostTokens());
        run.costUsd(entity.getCostUsd());
        run.budgetOverridden(entity.isBudgetOverridden());

        List<Step> steps = new ArrayList<>();
        for (StepEntity s : entity.getSteps()) {
            Step step = new Step(s.getId(), entity.getId(), s.getOrdinal(), s.getTitle(), s.getAgentRole(),
                    s.getToolName(), s.getParams() == null ? Map.of() : s.getParams());
            step.status(StepStatus.valueOf(s.getStatus().toUpperCase(Locale.ROOT)));
            step.decision(s.getDecision() == null ? null : Decision.valueOf(s.getDecision().toUpperCase(Locale.ROOT)));
            step.rejectReason(s.getRejectReason());
            step.result(s.getResult());
            step.error(s.getError());
            step.startedAt(s.getStartedAt());
            step.finishedAt(s.getFinishedAt());
            step.tokens(s.getTokens());
            step.costUsd(s.getCostUsd());
            step.attempts(s.getAttempts());
            step.paramsLocked(s.isParamsLocked());
            step.pausedBy(s.getPausedBy() == null
                    ? null : PauseReason.valueOf(s.getPausedBy().toUpperCase(Locale.ROOT)));
            steps.add(step);
        }
        run.replaceSteps(steps);

        for (AgentMessageEntity m : entity.getMessages()) {
            run.addMessage(new AgentMessage(m.getId(), entity.getId(), m.getStepId(), m.getFromAgent(),
                    m.getToAgent(), m.getContent(), m.getCreatedAt()));
        }
        return run;
    }
}
