package com.relay.api;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** What a caller who got the shape wrong is told to send instead. */
    static final String EXPECTED_SHAPE =
            "Politika listesi bekleniyor: [{\"toolName\": \"jira.createIssue\", \"mode\": \"ask\"}]";

    private final PolicyEngine policyEngine;

    public PolicyController(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
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

    /**
     * A batch of {@code {toolName, mode}} pairs.
     *
     * <p>Sending a single object instead of a list answered "İstek gövdesi okunamadı:
     * geçerli bir JSON gövdesi gönderin." — pointing at the JSON, which was perfectly
     * valid, instead of at the shape, which was not. The reader then goes looking for a
     * syntax error that is not there. That message stays for a body Jackson genuinely
     * cannot parse; a well-formed body of the wrong shape is told what shape to be.
     */
    @PutMapping
    public List<Map<String, Object>> update(@RequestBody JsonNode body) {
        if (body == null || !body.isArray()) {
            throw new IllegalArgumentException(EXPECTED_SHAPE);
        }
        for (JsonNode update : body) {
            String toolName = update.path("toolName").asText("").trim();
            String mode = update.path("mode").asText("").trim();
            if (toolName.isEmpty() || mode.isEmpty()) {
                throw new IllegalArgumentException(EXPECTED_SHAPE);
            }
            policyEngine.set(toolName, PolicyMode.fromWire(mode));
        }
        return list();
    }
}
