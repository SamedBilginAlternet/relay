package com.relay.api;

import com.relay.application.policy.PolicyEngine;
import com.relay.domain.PolicyMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-tool governance: auto | ask | forbidden. */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyEngine policyEngine;

    public PolicyController(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    public record PolicyUpdate(String toolName, String mode) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyEngine.EffectivePolicy policy : policyEngine.effectivePolicies()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", policy.provider());
            item.put("toolName", policy.toolName());
            item.put("risk", policy.risk().wire());
            item.put("mode", policy.mode().wire());
            item.put("overridden", policy.overridden());
            out.add(item);
        }
        return out;
    }

    /** Accepts one update or a batch. */
    @PutMapping
    public List<Map<String, Object>> update(@RequestBody List<PolicyUpdate> updates) {
        for (PolicyUpdate update : updates) {
            policyEngine.set(update.toolName(), PolicyMode.fromWire(update.mode()));
        }
        return list();
    }
}
