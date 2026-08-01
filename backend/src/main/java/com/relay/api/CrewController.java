package com.relay.api;

import com.relay.application.crew.CrewRoster;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.PolicyMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The crew, as the Ekip screen sees it. Read only, and derived from end to end.
 *
 * <p>There is no {@code POST} here and there never will be: a member is created by writing a
 * {@code Tool}, and its authority is changed through {@code PUT /api/policies}, which is a
 * human endpoint. This one only reports.
 *
 * <p>No Turkish in the payload. {@code AgentRole} ids go out as they are and
 * {@code frontend/src/lib/agents.ts} names them — the same rule the agent traffic already
 * follows, and the reason an id this build has never heard of still reaches the screen intact
 * instead of being translated into a word somebody made up.
 */
@RestController
@RequestMapping("/api/crew")
public class CrewController {

    private final CrewRoster roster;

    /**
     * The only constructor, deliberately: a second one would leave Spring with no way to
     * choose and the app would fail to start with nothing here to catch it — there is no
     * context test in this build.
     */
    public CrewController(ToolRegistry tools, PolicyEngine policyEngine, ConnectionRepository connections,
                          @Value("${app.llm.small-purposes:}") String smallPurposes) {
        this.roster = new CrewRoster(tools, policyEngine, connections, parsePurposes(smallPurposes));
    }

    /** Same parsing as {@code LlmConfig}: blank means the shipped default, not "nothing". */
    private static List<String> parsePurposes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(purpose -> !purpose.isEmpty())
                .toList();
    }

    @GetMapping
    public Map<String, Object> crew() {
        CrewRoster.Crew crew = roster.crew();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("core", crew.core().stream().map(CrewController::core).toList());
        body.put("members", crew.members().stream().map(CrewController::member).toList());
        return body;
    }

    private static Map<String, Object> core(CrewRoster.CoreMember member) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", member.id());
        item.put("purpose", member.purpose());
        item.put("tier", member.tier());
        return item;
    }

    private static Map<String, Object> member(CrewRoster.Specialist member) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", member.id());
        item.put("provider", member.provider());
        item.put("connectionProvider", member.connectionProvider());
        item.put("connected", member.connected());
        item.put("toolCount", member.tools().size());
        // Flat counts rather than a nested object: this is the line the screen reads out
        // loud — "four reads by itself, three writes ask you" — and it is read far more
        // often than the tool list under it.
        for (PolicyMode mode : PolicyMode.values()) {
            item.put(mode.wire(), member.authority().getOrDefault(mode, 0));
        }
        item.put("purpose", member.purpose());
        item.put("tier", member.tier());
        List<Map<String, Object>> tools = new ArrayList<>();
        for (CrewRoster.Held held : member.tools()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", held.toolName());
            tool.put("risk", held.risk().wire());
            tool.put("mode", held.mode().wire());
            tool.put("overridden", held.overridden());
            tools.add(tool);
        }
        item.put("tools", tools);
        return item;
    }
}
