package com.relay.infrastructure.persistence;

import com.relay.application.port.PolicyRepository;
import com.relay.domain.PolicyMode;
import com.relay.domain.ToolPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaPolicyRepository implements PolicyRepository {

    private final ToolPolicyEntityRepository policies;

    public JpaPolicyRepository(ToolPolicyEntityRepository policies) {
        this.policies = policies;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolPolicy> findAll() {
        List<ToolPolicy> out = new ArrayList<>();
        policies.findAll().forEach(entity -> out.add(toDomain(entity)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ToolPolicy> findByToolName(String toolName) {
        return policies.findById(toolName).map(this::toDomain);
    }

    @Override
    @Transactional
    public ToolPolicy save(ToolPolicy policy) {
        ToolPolicyEntity entity = policies.findById(policy.toolName()).orElseGet(ToolPolicyEntity::new);
        entity.setToolName(policy.toolName());
        entity.setProvider(policy.provider());
        entity.setMode(policy.mode().wire());
        policies.save(entity);
        return policy;
    }

    private ToolPolicy toDomain(ToolPolicyEntity entity) {
        return new ToolPolicy(entity.getProvider(), entity.getToolName(), PolicyMode.fromWire(entity.getMode()));
    }
}
