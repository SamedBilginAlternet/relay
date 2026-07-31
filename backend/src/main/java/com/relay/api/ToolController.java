package com.relay.api;

import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The registry, as the frontend and the LLM see it. */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry tools;
    private final PolicyEngine policyEngine;

    public ToolController(ToolRegistry tools, PolicyEngine policyEngine) {
        this.tools = tools;
        this.policyEngine = policyEngine;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool tool : tools.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("provider", tool.provider());
            item.put("description", tool.description());
            item.put("risk", tool.risk().wire());
            item.put("defaultMode", tool.risk().defaultMode().wire());
            item.put("mode", policyEngine.evaluate(tool.name()).mode().wire());
            item.put("schema", tool.schema());
            out.add(item);
        }
        return out;
    }
}
