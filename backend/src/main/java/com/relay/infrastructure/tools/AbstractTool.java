package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.SchemaValidator;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Shared plumbing for every integration: timing, schema gate, error mapping and the
 * live↔replay switch.
 *
 * <p>Replay is used when {@code TOOLS_MODE=replay} <em>or</em> when the provider has no
 * connection configured — so a fresh install (and demo day without accounts) still runs
 * the whole workflow end to end.
 */
public abstract class AbstractTool implements Tool {

    private static final Logger LOG = System.getLogger(AbstractTool.class.getName());

    protected final ToolsMode mode;
    protected final FixtureStore fixtures;

    protected AbstractTool(ToolsMode mode, FixtureStore fixtures) {
        this.mode = mode;
        this.fixtures = fixtures;
    }

    /** The real API call. Only invoked in live mode with a configured connection. */
    protected abstract JsonNode call(JsonNode params, Connection connection) throws Exception;

    @Override
    public ToolResult execute(JsonNode params, Connection connection) {
        long start = System.nanoTime();
        SchemaValidator.Result check = SchemaValidator.validate(schema(), params);
        if (!check.valid()) {
            return ToolResult.error("invalid params: " + check.message(), elapsed(start), "rejected");
        }

        boolean replay = mode == ToolsMode.REPLAY || connection == null || !usable(connection);
        String effectiveMode = mode == ToolsMode.REPLAY ? "replay"
                : (replay ? "replay (no connection)" : "live");
        try {
            JsonNode data = replay ? fixtures.load(name(), params) : call(params, connection);
            return ToolResult.ok(data, elapsed(start), effectiveMode);
        } catch (Exception e) {
            // Never let a provider message carry a token into the log.
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.log(Level.WARNING, "tool {0} failed in {1} mode: {2}", name(), effectiveMode, message);
            return ToolResult.error(message, elapsed(start), effectiveMode);
        }
    }

    /** Does the connection carry what this provider needs? */
    protected boolean usable(Connection connection) {
        return true;
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
