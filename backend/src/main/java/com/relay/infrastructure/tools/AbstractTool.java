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

    /**
     * Narrows a provider's answer to the fields this product reads.
     *
     * <p>A tool result is not an internal value. It goes onto the audit trail, down the SSE
     * stream into a browser, and into the next step's prompt. One raw Jira search body
     * carried forty of Atlassian's own REST URLs, twenty icon URLs, an {@code expand} list
     * and a pagination token whose base64 decoded to their internal tenant state — none of
     * it read by anything, all of it on screen. docs/NASIL-CALISIYOR.md §3 already says a
     * raw provider message is never passed through, because it can hold a URL, a request
     * body or a token; that promise was kept for failures and broken for successes.
     *
     * <p>Abstract on purpose: a new integration has to say what leaves it. Returning
     * {@code raw} is a perfectly good answer for a tool that already builds its own reply
     * in {@link #call} — it just has to be said out loud rather than assumed.
     *
     * <p>Replayed fixtures go through it too, so live and replay cannot drift apart: a
     * projection that changes the fixture is a projection that is wrong about the shape.
     */
    protected abstract JsonNode project(JsonNode raw);

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
            return ToolResult.ok(project(data), elapsed(start), effectiveMode);
        } catch (Exception e) {
            // Never let a provider message carry a token into the log.
            String message = describe(e);
            LOG.log(Level.WARNING, "tool {0} failed in {1} mode: {2}", name(), effectiveMode, message);
            return ToolResult.error(message, elapsed(start), effectiveMode);
        }
    }

    /**
     * What the user is told when a tool fails.
     *
     * <p>A {@link HttpJson.ToolCallException} is already a sentence someone wrote for this
     * moment ("Jira'da 'RELAY' anahtarlı bir proje yok…"); prefixing it with the class name
     * put "ToolCallException:" on the timeline in front of it. Anything else is unplanned, so
     * the type is the most useful thing about it — and its message goes through redaction,
     * because nobody vetted what a stray exception put in there.
     */
    static String describe(Exception e) {
        if (e instanceof HttpJson.ToolCallException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + HttpJson.redact(String.valueOf(e.getMessage()));
    }

    /** Does the connection carry what this provider needs? */
    protected boolean usable(Connection connection) {
        return true;
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
