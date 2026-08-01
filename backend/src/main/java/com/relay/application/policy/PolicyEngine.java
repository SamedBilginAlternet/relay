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

    /**
     * Steps without a tool are pure reasoning: always auto.
     *
     * <p>{@code reason} is read by a person, not by a log: the approval card shows it as the
     * grounds for asking, and the timeline repeats it on every gated step. It used to say
     * "default for write risk: ask" in the middle of an otherwise Turkish product — on the
     * single most-read line in the app (#81).
     */
    public PolicyDecision evaluate(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return new PolicyDecision(PolicyMode.AUTO, "araç çağrısı yok — düşünme adımı", false);
        }
        Optional<Tool> tool = tools.find(toolName);
        if (tool.isEmpty()) {
            return new PolicyDecision(PolicyMode.FORBIDDEN, "tanımsız araç '" + toolName + "'", true);
        }
        RiskLevel risk = tool.get().risk();
        Optional<ToolPolicy> override = policies.findByToolName(toolName);
        if (override.isPresent()) {
            PolicyMode wanted = override.get().mode();
            PolicyMode mode = capped(risk, wanted);
            if (mode != wanted) {
                return new PolicyDecision(mode, toolName + " için kayıtlı politika "
                        + label(wanted) + " istiyor, " + label(mode) + " ile sınırlandı: "
                        + "geri alınamaz bir araç insansız çalışmaz", true);
            }
            return new PolicyDecision(mode,
                    toolName + " için kayıtlı politika: " + label(mode), true);
        }
        PolicyMode mode = risk.defaultMode();
        return new PolicyDecision(mode,
                label(risk) + " riski varsayılanı: " + label(mode), false);
    }

    /** How a mode is named to the person being asked to approve something. */
    private static String label(PolicyMode mode) {
        return switch (mode) {
            case AUTO -> "otomatik";
            case ASK -> "onay ister";
            case FORBIDDEN -> "yasak";
        };
    }

    private static String label(RiskLevel risk) {
        return switch (risk) {
            case READ -> "okuma";
            case WRITE -> "yazma";
            case DESTRUCTIVE -> "yıkıcı";
        };
    }

    /**
     * The one thing an operator may not decide: that an irreversible tool runs unwatched.
     *
     * <p>The override used to win unconditionally, which meant the most dangerous tool in the
     * registry could be put on full automatic with a single {@code PUT /api/policies} — one
     * request, no second pair of eyes, and nothing to undo afterwards. Relaxing a destructive
     * tool to {@code ask} is a real need (someone has to be able to run the delete at all), so
     * the override still counts; it just cannot skip the human.
     *
     * <p>Applied on read as well as on write. {@link #set} refuses the request in the first
     * place, but a row can also arrive from a database written by an older build or by hand,
     * and a guarantee that only holds when the API was used is not a guarantee.
     */
    private static PolicyMode capped(RiskLevel risk, PolicyMode wanted) {
        return risk == RiskLevel.DESTRUCTIVE && wanted == PolicyMode.AUTO ? PolicyMode.ASK : wanted;
    }

    /** Effective policy for every registered tool — what {@code GET /api/policies} returns. */
    public List<EffectivePolicy> effectivePolicies() {
        Map<String, ToolPolicy> overrides = new LinkedHashMap<>();
        policies.findAll().forEach(p -> overrides.put(p.toolName(), p));
        List<EffectivePolicy> out = new ArrayList<>();
        for (Tool tool : tools.all()) {
            ToolPolicy override = overrides.get(tool.name());
            PolicyMode mode = override != null
                    ? capped(tool.risk(), override.mode())
                    : tool.risk().defaultMode();
            out.add(new EffectivePolicy(tool.provider(), tool.name(), tool.risk(), mode, override != null));
        }
        return out;
    }

    /**
     * Writes an operator's decision — except the one decision that is not theirs to make.
     *
     * @throws IllegalArgumentException when a destructive tool is asked to run automatically;
     *                                  answered as 400, with this sentence on screen
     */
    public ToolPolicy set(String toolName, PolicyMode mode) {
        Tool tool = tools.find(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Tanımsız araç: " + toolName));
        if (tool.risk() == RiskLevel.DESTRUCTIVE && mode == PolicyMode.AUTO) {
            throw new IllegalArgumentException(toolName + " geri alınamaz bir araç (destructive):"
                    + " ask ya da forbidden yapılabilir, auto yapılamaz — silme ile kullanıcı"
                    + " arasında bir insan kalmalı.");
        }
        return policies.save(new ToolPolicy(tool.provider(), toolName, mode));
    }

    public record EffectivePolicy(String provider, String toolName, RiskLevel risk, PolicyMode mode,
                                  boolean overridden) {
    }
}
