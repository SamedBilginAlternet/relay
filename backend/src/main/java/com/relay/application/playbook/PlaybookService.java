package com.relay.application.playbook;

import com.relay.application.orchestrator.RunService;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue of written-down flows, and the one way to start them.
 *
 * <p>A playbook is filtered against what is actually connected before it runs: the morning
 * round-up still works on a laptop with only Jira, it just does not read mail. A step whose
 * provider is missing and which is <em>not</em> optional makes the whole playbook
 * unavailable — better to grey out the button than to start a flow that dead-ends.
 */
public class PlaybookService {

    private final ToolRegistry tools;
    private final ConnectionRepository connections;
    private final RunService runs;

    public PlaybookService(ToolRegistry tools, ConnectionRepository connections, RunService runs) {
        this.tools = tools;
        this.connections = connections;
        this.runs = runs;
    }

    public List<Playbook> all() {
        return Playbooks.ALL;
    }

    /** Catalogue for the API, each entry marked with whether it can run right now. */
    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Playbook playbook : Playbooks.ALL) {
            Map<String, Object> view = new LinkedHashMap<>(playbook.view());
            List<String> missing = missingProviders(playbook);
            view.put("runnable", missing.isEmpty());
            view.put("missing", missing);
            out.add(view);
        }
        return out;
    }

    /** Providers a required step needs but nobody connected. Empty means good to go. */
    private List<String> missingProviders(Playbook playbook) {
        List<String> missing = new ArrayList<>();
        for (Playbook.Move move : playbook.steps()) {
            if (move.optional() || available(move.toolName())) {
                continue;
            }
            // An unregistered tool counts as missing too — otherwise a typo in a playbook
            // would read as "everything is fine" until the run dies on step one.
            String provider = provider(move.toolName())
                    .orElseGet(() -> move.toolName().split("\\.")[0]);
            if (!missing.contains(provider)) {
                missing.add(provider);
            }
        }
        return missing;
    }

    public Run start(String id, Double budgetUsd) {
        Playbook playbook = Playbooks.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown playbook: " + id));

        List<String> missing = missingProviders(playbook);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("playbook " + id + " needs: " + String.join(", ", missing));
        }

        List<RunService.SeedStep> seeds = new ArrayList<>();
        for (Playbook.Move move : playbook.steps()) {
            if (move.optional() && !available(move.toolName())) {
                continue;
            }
            seeds.add(new RunService.SeedStep(move.title(), move.toolName(), move.params()));
        }
        return runs.startFromPlaybook(playbook.goal(), playbook.title(), seeds, budgetUsd);
    }

    /** Registered and holding credentials — a tool in replay mode is not a connected tool. */
    private boolean available(String toolName) {
        return provider(toolName)
                .flatMap(connections::findByProvider)
                .filter(connection -> !connection.config().isEmpty())
                .isPresent();
    }

    private Optional<String> provider(String toolName) {
        return tools.find(toolName).map(Tool::provider);
    }
}
