package com.relay.api;

import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.RoutingLlmClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code {status, version, llm}} — plus what mode the tools are in. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final String version;
    private final String toolsMode;

    public HealthController(LlmClient llm, ToolRegistry tools,
                            @Value("${app.version:0.1.0}") String version,
                            @Value("${app.tools.mode:replay}") String toolsMode) {
        this.llm = llm;
        this.tools = tools;
        this.version = version;
        this.toolsMode = toolsMode;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", version);
        body.put("llm", llm instanceof RoutingLlmClient routing
                ? routing.health()
                : Map.of("provider", llm.name(), "degraded", llm.degraded()));
        Map<String, Object> toolInfo = new LinkedHashMap<>();
        toolInfo.put("mode", toolsMode);
        toolInfo.put("count", tools.all().size());
        body.put("tools", toolInfo);
        return body;
    }
}
