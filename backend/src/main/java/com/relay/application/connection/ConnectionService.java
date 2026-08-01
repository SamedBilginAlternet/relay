package com.relay.application.connection;

import com.relay.application.json.Json;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Credential handling: merge, mask, test. Controllers only forward.
 *
 * <p>Masked values coming back from the UI are ignored on save, so re-saving a form
 * that shows {@code xoxb-****1234} does not overwrite the real token with stars.
 */
public class ConnectionService {

    public static final String MASK_MARKER = "****";
    // github: fine-grained PAT. google: OAuth, filled in by /api/oauth/google/callback.
    // notion: internal integration token (ntn_…), pasted like Jira's and Slack's.
    private static final List<String> KNOWN_PROVIDERS =
            List.of("jira", "slack", "github", "google", "notion");

    private final ConnectionRepository connections;
    private final ToolRegistry tools;
    private final Clock clock;

    public ConnectionService(ConnectionRepository connections, ToolRegistry tools, Clock clock) {
        this.connections = connections;
        this.tools = tools;
        this.clock = clock;
    }

    public List<String> providers() {
        return KNOWN_PROVIDERS;
    }

    public List<Connection> all() {
        return connections.findAll();
    }

    public Optional<Connection> byProvider(String provider) {
        return connections.findByProvider(provider);
    }

    /**
     * Upsert; masked incoming values keep the stored secret.
     *
     * <p>The provider has to be one Relay actually has tools for. {@code PUT} used to store
     * any name at all and answer {@code {"provider":"evilcorp","configured":true}} — a
     * credential accepted for something that can never be called, invisible in
     * {@code GET /api/connections} (which lists the four known ones) and impossible to
     * delete. An API that says it saved something has to have saved it somewhere the rest
     * of the API can see.
     */
    public Connection save(String provider, Map<String, String> incoming) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Sağlayıcı adı gerekli.");
        }
        if (!KNOWN_PROVIDERS.contains(provider.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Bilinmeyen sağlayıcı: " + provider
                    + ". Tanımlı olanlar: " + String.join(", ", KNOWN_PROVIDERS) + ".");
        }
        Connection existing = connections.findByProvider(provider).orElse(null);
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing.config());
        }
        if (incoming != null) {
            incoming.forEach((key, value) -> {
                if (value == null) {
                    return;
                }
                if (value.contains(MASK_MARKER)) {
                    return;
                }
                merged.put(key, value.trim());
            });
        }
        Connection connection = existing == null
                ? Connection.of(provider, merged, clock.now())
                : new Connection(existing.id(), provider, merged, existing.createdAt());
        return connections.save(connection);
    }

    /**
     * Calls the provider's cheapest READ tool. In replay mode this proves the wiring,
     * in live mode it proves the credentials.
     */
    public Map<String, Object> test(String provider) {
        // Prefer search/list style probes: they need no real entity key, so the test
        // proves credentials instead of failing on a fabricated issue id (KAN vs RELAY-1).
        Tool probe = tools.all().stream()
                .filter(tool -> tool.provider().equals(provider))
                .filter(tool -> tool.risk() == RiskLevel.READ)
                .min((a, b) -> Boolean.compare(!isKeyless(a), !isKeyless(b)))
                // Read by whoever pressed "Bağlantıyı Test Et", so it is written in the
                // language that button is in (#81).
                .orElseThrow(() -> new IllegalArgumentException(
                        provider + " için kayıtlı bir okuma aracı yok."));

        Connection connection = connections.findByProvider(provider).orElse(null);
        ToolResult result = probe.execute(probeParams(probe, connection), connection);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("tool", probe.name());
        out.put("ok", result.ok());
        out.put("mode", result.mode());
        out.put("durationMs", result.durationMs());
        out.put("error", result.error());
        out.put("sample", result.ok() ? Json.preview(Json.toPlain(result.data()), 400) : null);
        return out;
    }

    private static boolean isKeyless(Tool tool) {
        String n = tool.name().toLowerCase();
        return n.contains("search") || n.contains("list");
    }

    private com.fasterxml.jackson.databind.JsonNode probeParams(Tool probe, Connection connection) {
        var params = Json.object();
        if (probe.schema().has("required")) {
            for (var required : probe.schema().get("required")) {
                String field = required.asText();
                if (field.toLowerCase().contains("jql")) {
                    params.put(field, probeJql(connection));
                } else if (field.toLowerCase().contains("issuekey")) {
                    params.put(field, "RELAY-1");
                } else {
                    params.put(field, "relay-connection-test");
                }
            }
        }
        return params;
    }

    /**
     * Jira Cloud rejects an unbounded JQL on {@code /search/jql} with HTTP 400
     * ("Burada sınırsız JQL'lere izin verilmez"), so a bare {@code order by updated desc}
     * probe fails even with perfect credentials. Bound it: by the configured project when
     * there is one, otherwise by the harmless global restriction.
     */
    private static String probeJql(Connection connection) {
        String project = connection == null ? null : connection.config().get("defaultProject");
        return project == null || project.isBlank()
                // `project is not EMPTY` is the documented harmless bound: it restricts
                // nothing in practice but satisfies the validator on every Jira flavour.
                ? "project is not EMPTY ORDER BY updated DESC"
                : "project = \"" + project.trim() + "\" ORDER BY updated DESC";
    }

    /** Provider list with masked config — safe for the API. */
    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String provider : KNOWN_PROVIDERS) {
            Connection connection = connections.findByProvider(provider).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", provider);
            item.put("configured", connection != null && !connection.config().isEmpty());
            item.put("config", connection == null ? Map.of()
                    : Masking.maskConfig(connection.config()));
            item.put("createdAt", connection == null ? null : connection.createdAt().toString());
            out.add(item);
        }
        return out;
    }
}
