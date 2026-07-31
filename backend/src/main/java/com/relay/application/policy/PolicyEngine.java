package com.relay.application.policy;

import com.relay.application.port.PolicyRepository;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.PolicyMode;
import com.relay.domain.RiskLevel;
import com.relay.domain.ToolPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Governance, and nothing else.
 *
 * <p>Defaults come from the tool's risk level (READ→auto, WRITE→ask, DESTRUCTIVE→forbidden);
 * an operator override in {@link PolicyRepository} wins over the default.
 */
public class PolicyEngine {

    private final PolicyRepository policies;
    private final ToolRegistry tools;

    public PolicyEngine(PolicyRepository policies, ToolRegistry tools) {
        this.policies = policies;
        this.tools = tools;
    }

    /** Steps without a tool are pure reasoning: always auto. */
    public PolicyDecision evaluate(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return new PolicyDecision(PolicyMode.AUTO, "no tool call — reasoning step", false);
        }
        Optional<Tool> tool = tools.find(toolName);
        if (tool.isEmpty()) {
            return new PolicyDecision(PolicyMode.FORBIDDEN, "unknown tool '" + toolName + "'", true);
        }
        RiskLevel risk = tool.get().risk();
        Optional<ToolPolicy> override = policies.findByToolName(toolName);
        if (override.isPresent()) {
            PolicyMode mode = override.get().mode();
            return new PolicyDecision(mode, "policy override for " + toolName + ": " + mode.wire(), true);
        }
        PolicyMode mode = risk.defaultMode();
        return new PolicyDecision(mode, "default for " + risk.wire() + " risk: " + mode.wire(), false);
    }

    /** Effective policy for every registered tool — what {@code GET /api/policies} returns. */
    public List<EffectivePolicy> effectivePolicies() {
        Map<String, ToolPolicy> overrides = new LinkedHashMap<>();
        policies.findAll().forEach(p -> overrides.put(p.toolName(), p));
        List<EffectivePolicy> out = new ArrayList<>();
        for (Tool tool : tools.all()) {
            ToolPolicy override = overrides.get(tool.name());
            PolicyMode mode = override != null ? override.mode() : tool.risk().defaultMode();
            out.add(new EffectivePolicy(tool.provider(), tool.name(), tool.risk(), mode, override != null));
        }
        return out;
    }

    public ToolPolicy set(String toolName, PolicyMode mode) {
        Tool tool = tools.find(toolName)
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + toolName));
        return policies.save(new ToolPolicy(tool.provider(), toolName, mode));
    }

    public record EffectivePolicy(String provider, String toolName, RiskLevel risk, PolicyMode mode,
                                  boolean overridden) {
    }
}
