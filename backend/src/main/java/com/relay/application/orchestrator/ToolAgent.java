package com.relay.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The specialist. Knows one tool per step: finalises the parameters against that
 * tool's schema (using earlier step results as context) and calls it.
 *
 * <p>A step without a tool is a reasoning step and is answered by the LLM directly.
 */
public class ToolAgent {

    private final ToolRegistry tools;
    private final LlmClient llm;
    private final ConnectionRepository connections;
    private final AgentJournal journal;
    private final Clock clock;

    public ToolAgent(ToolRegistry tools, LlmClient llm, ConnectionRepository connections,
                     AgentJournal journal, Clock clock) {
        this.tools = tools;
        this.llm = llm;
        this.connections = connections;
        this.journal = journal;
        this.clock = clock;
    }

    public StepOutcome execute(Run run, Step step) {
        String agent = step.role() == null ? AgentRole.toolAgent(step.toolName()) : step.role();
        journal.say(run, step.id(), AgentRole.COORDINATOR, agent,
                "Adım " + step.ordinal() + " sende: " + step.title());

        if (step.toolName() == null || step.toolName().isBlank()) {
            return reason(run, step, agent);
        }

        Tool tool = tools.find(step.toolName()).orElse(null);
        if (tool == null) {
            return StepOutcome.failed("unknown tool: " + step.toolName(), 0, 0);
        }

        ParamOutcome params = finaliseParams(run, step, tool);
        if (!params.valid()) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                    "Parametreler şemaya uymadı: " + params.error());
            return StepOutcome.failed("parameter validation failed: " + params.error(),
                    params.tokens(), params.costUsd());
        }
        step.params(Json.toMap(params.params()));

        Connection connection = connections.findByProvider(tool.provider()).orElse(null);

        String invented = ungroundedIdentifier(run, step, tool, params.params(), connection);
        if (invented != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, invented);
            return StepOutcome.failed(invented, params.tokens(), params.costUsd());
        }

        String empty = emptyContent(tool, params.params());
        if (empty != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, empty);
            return StepOutcome.failed(empty, params.tokens(), params.costUsd());
        }

        String placeholder = unresolvedPlaceholder(tool, params.params());
        if (placeholder != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, placeholder);
            return StepOutcome.failed(placeholder, params.tokens(), params.costUsd());
        }

        journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                tool.name() + " çağrılıyor: " + Json.preview(step.params(), 240));

        ToolResult result;
        try {
            result = tool.execute(params.params(), connection);
        } catch (RuntimeException e) {
            return StepOutcome.failed(tool.name() + " threw: " + e.getMessage(),
                    params.tokens(), params.costUsd());
        }

        if (!result.ok()) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                    tool.name() + " hata verdi: " + result.error());
            return StepOutcome.failed(result.error(), params.tokens(), params.costUsd());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool.name());
        payload.put("mode", result.mode());
        payload.put("durationMs", result.durationMs());
        payload.put("data", Json.toPlain(result.data()));

        journal.say(run, step.id(), agent, AgentRole.VERIFIER,
                tool.name() + " tamam (" + result.durationMs() + " ms), sonuç doğrulamaya gidiyor.");
        return StepOutcome.ok(payload, params.tokens(), params.costUsd());
    }

    // ---- reasoning step ---------------------------------------------------

    private StepOutcome reason(Run run, Step step, String agent) {
        LlmRequest request = LlmRequest.of(
                LlmPurpose.SUMMARIZE,
                "You are a Relay agent. Answer the step using the goal and the previous results. Be brief.",
                "GOAL:\n" + run.goal() + "\n\nSTEP: " + step.title()
                        + "\n\nPREVIOUS RESULTS:\n" + Json.preview(previousResults(run, step), 2000),
                null,
                Map.of("goal", run.goal(), "step", step.title()));
        LlmResponse response = llm.complete(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", null);
        payload.put("mode", "llm");
        payload.put("text", response.content());
        journal.say(run, step.id(), agent, AgentRole.VERIFIER, "Metin üretildi, doğrulamaya gidiyor.");
        return StepOutcome.ok(payload, response.totalTokens(), response.costUsd());
    }

    // ---- grounding --------------------------------------------------------

    /** Marker the coordinator recognises so it can repair the plan instead of giving up. */
    public static final String UNGROUNDED = "uydurulmuş tanımlayıcı";

    public static boolean ungrounded(String error) {
        return error != null && error.contains(UNGROUNDED);
    }

    /**
     * Drops the invented identifiers so the step has to derive them again.
     *
     * <p>Without this the repaired step would keep {@code issueKey=RELAY-1} in its draft,
     * the draft would still satisfy the schema, no model call would happen, and the lookup
     * step we just inserted would change nothing.
     */
    public static Map<String, Object> withoutIdentifiers(Map<String, Object> params) {
        Map<String, Object> kept = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!isIdentifier(key)) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    /**
     * The cheapest READ tool of the same provider — the step to run <em>before</em> a write
     * whose identifier came from nowhere. Search/list style tools need no entity key
     * themselves, so they can always run first.
     */
    public java.util.Optional<String> lookupToolFor(String writeToolName) {
        return tools.find(writeToolName)
                .flatMap(write -> tools.all().stream()
                        .filter(candidate -> candidate.provider().equals(write.provider()))
                        .filter(candidate -> candidate.risk() == com.relay.domain.RiskLevel.READ)
                        .filter(candidate -> {
                            String name = candidate.name().toLowerCase();
                            return name.contains("search") || name.contains("list");
                        })
                        .map(Tool::name)
                        .findFirst());
    }

    /**
     * Stops a parameter that still contains a substitution nobody is going to perform.
     *
     * <p>Slack was handed the channel {@code {{steps[3].channel}}} and answered
     * {@code channel_not_found} — a confusing error for a channel that exists, because the
     * value sent was never a channel name. Relay has no template engine: a step reads the
     * earlier results and writes the real value, so a template marker that survives this far
     * is a parameter the model declined to fill.
     *
     * <p>Applies to reads as well. A JQL with {@code {{…}}} in it is just as broken, and the
     * provider's own error will not say so.
     *
     * @return the message to fail with, or {@code null} when every value is concrete
     */
    private String unresolvedPlaceholder(Tool tool, JsonNode params) {
        var fields = params.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isTextual()) {
                continue;
            }
            String value = field.getValue().asText();
            if (com.relay.application.text.Placeholder.unresolved(value)) {
                return tool.name() + " için çözülmemiş yer tutucu: " + field.getKey() + "=" + value
                        + ". Bu değer önceki adımdan alınacaktı ama doldurulmadı — sağlayıcıya"
                        + " gönderilmedi.";
            }
        }
        return null;
    }

    /** Fields whose value a person reads: the message, not the plumbing. */
    private static final java.util.Set<String> HUMAN_TEXT_FIELDS = java.util.Set.of(
            "text", "message", "body", "comment", "description");

    /**
     * Refuses to send a message that reports activity instead of findings.
     *
     * <p>This is the last gate before a provider call, and it is deliberately dumb: it
     * matches known placeholder phrasing rather than judging quality. The fallback model
     * writes exactly those phrases, so when Groq is rate limited the run stops here with a
     * reason instead of posting "adımlar yürütüldü" into someone's Slack channel.
     *
     * @return the message to fail with, or {@code null} when the content carries something
     */
    private String emptyContent(Tool tool, JsonNode params) {
        if (tool.risk() == com.relay.domain.RiskLevel.READ) {
            return null;
        }
        var fields = params.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!HUMAN_TEXT_FIELDS.contains(field.getKey().toLowerCase()) || !field.getValue().isTextual()) {
                continue;
            }
            if (com.relay.application.text.Filler.looksLikeFiller(field.getValue().asText())) {
                return tool.name() + " için içerik üretilemedi: " + field.getKey()
                        + " alanı yalnızca şablon metin taşıyor. Dil modeli yedekteyse"
                        + " (GROQ_API_KEYS) gerçek içerik yazılamaz — mesaj gönderilmedi.";
            }
        }
        return null;
    }

    /**
     * Refuses to write to an entity nobody ever mentioned.
     *
     * <p>Asked to "close this", the planner happily produced
     * {@code jira.updateIssue {issueKey: RELAY-1}} — a key it made up. Jira answered 404,
     * which was the lucky outcome: on a tenant where that key exists, Relay would have
     * closed a stranger's issue. So an identifier on a writing step has to come from
     * somewhere real — the goal, an earlier step's result, or the connection settings.
     *
     * @return the message to fail with, or {@code null} when everything checks out
     */
    private String ungroundedIdentifier(Run run, Step step, Tool tool, JsonNode params,
                                        Connection connection) {
        if (tool.risk() == com.relay.domain.RiskLevel.READ) {
            return null;
        }
        String haystack = (run.goal() + " " + Json.preview(previousResults(run, step), 4000)
                + " " + (connection == null ? "" : String.join(" ", connection.config().values())))
                .toLowerCase();

        var fields = params.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!isIdentifier(field.getKey()) || !field.getValue().isTextual()) {
                continue;
            }
            String value = field.getValue().asText().trim();
            if (value.isEmpty() || value.contains(" ") || haystack.contains(value.toLowerCase())) {
                continue;
            }
            return tool.name() + " için " + UNGROUNDED + ": " + field.getKey() + "=" + value
                    + ". Bu kayıt ne hedefte ne de önceki adımların sonucunda geçiyor —"
                    + " önce onu bulan bir arama adımı gerekiyor.";
        }
        return null;
    }

    /**
     * Fields naming a <em>container</em> to write into rather than a record to change:
     * a project to create an issue in, a channel to post to, a repository to comment under.
     * Those come from connection defaults or from the user's own setup, so demanding that
     * the goal mention them would block ordinary work. Getting one wrong costs a 400, not
     * a stranger's issue.
     */
    private static final java.util.Set<String> CONTAINER_FIELDS = java.util.Set.of(
            "projectkey", "project", "repo", "repository", "owner", "channel", "channelid");

    /** Names that point at one specific, already existing record. */
    private static boolean isIdentifier(String field) {
        String name = field.toLowerCase();
        if (CONTAINER_FIELDS.contains(name)) {
            return false;
        }
        return name.endsWith("key") || name.endsWith("id") || name.endsWith("number");
    }

    // ---- parameters -------------------------------------------------------

    /** What one out-of-band parameter refresh cost. */
    public record ParamRefresh(boolean ok, long tokens, double costUsd) {
    }

    /**
     * Re-derives a step's parameters without calling the tool.
     *
     * <p>Used when a write is going back to the approval gate after the provider rejected
     * it: the human must see the parameters that will actually be sent, not the ones that
     * already failed.
     */
    public ParamRefresh refreshParams(Run run, Step step) {
        Tool tool = tools.find(step.toolName()).orElse(null);
        if (tool == null) {
            return new ParamRefresh(false, 0, 0);
        }
        ParamOutcome outcome = finaliseParams(run, step, tool);
        if (outcome.valid()) {
            step.params(Json.toMap(outcome.params()));
        }
        return new ParamRefresh(outcome.valid(), outcome.tokens(), outcome.costUsd());
    }

    private record ParamOutcome(boolean valid, JsonNode params, String error, long tokens, double costUsd) {
    }

    private ParamOutcome finaliseParams(Run run, Step step, Tool tool) {
        JsonNode draft = Json.toNode(step.params());
        if (!draft.isObject()) {
            draft = Json.object();
        }
        // Before the model sees the draft: what the user configured beats what a model would
        // invent. A blank channel filled in from the connection is also one fewer model call.
        Connection connection = connections.findByProvider(tool.provider()).orElse(null);
        draft = tool.withDefaults(draft, connection);

        long tokens = 0;
        double cost = 0;
        JsonNode candidate = draft;

        SchemaValidator.Result draftCheck = SchemaValidator.validate(tool.schema(), draft);
        // A previous attempt that the provider rejected has to be reconsidered even when the
        // draft is schema-valid: the schema was never the problem, the values were.
        if (!draftCheck.valid() || step.lastProviderError() != null) {
            // Only spend a model call when the draft is not already good enough.
            LlmRequest request = LlmRequest.of(
                    LlmPurpose.TOOL_PARAMS,
                    "You are the " + tool.provider() + " specialist agent of Relay. "
                            + "Produce ONLY the JSON parameter object for the tool call. No prose.\n"
                            // The message body is the product here: a Slack post that says
                            // "özet gönderildi, ayrıntılar zaman çizelgesinde" is worse than
                            // no message at all — the reader learns nothing and has to go
                            // digging, which is the exact work Relay claims to remove.
                            + "Any field a human will read (message text, description, comment) "
                            + "must carry the actual content: name the records by key, give the "
                            + "counts, say what state they are in. Write it in the language of "
                            + "the goal. Never write placeholders like \"özet\", \"detaylar "
                            + "aşağıda\", \"ayrıntılar zaman çizelgesinde\", \"TODO\" or "
                            + "\"<...>\" — if the facts are in PREVIOUS RESULTS, put them in the text.",
                    "GOAL:\n" + run.goal()
                            + "\n\nSTEP: " + step.title()
                            + "\n\nTOOL: " + tool.name() + " — " + tool.description()
                            + "\n\nPARAM SCHEMA:\n" + tool.schema().toString()
                            + "\n\nDRAFT PARAMS:\n" + draft
                            + "\n\nPREVIOUS RESULTS:\n" + Json.preview(previousResults(run, step), 2000)
                            + (step.lastProviderError() == null ? ""
                                    : "\n\nThe last attempt was rejected by the provider: " + step.lastProviderError()
                                            + "\nFix the parameters accordingly — the provider's message"
                                            + " usually names the allowed values.")
                            + "\n\nProblems with the draft: " + draftCheck.message(),
                    tool.schema(),
                    Map.of("tool", tool.name(),
                            "draft", Json.toPlain(draft),
                            "goal", run.goal(),
                            "previous", previousResults(run, step)));
            LlmResponse response = llm.complete(request);
            tokens = response.totalTokens();
            cost = response.costUsd();
            JsonNode fromModel = Json.extract(response.content());
            if (fromModel != null && fromModel.isObject()) {
                candidate = merge(draft, fromModel);
            }
        }

        SchemaValidator.Result check = SchemaValidator.validate(tool.schema(), candidate);
        if (!check.valid()) {
            return new ParamOutcome(false, candidate, check.message(), tokens, cost);
        }
        // Applied again in case the model overwrote a resolved value with a placeholder.
        // Defaults land here rather than at call time so the parameters a human approves are
        // the parameters that get sent.
        return new ParamOutcome(true, tool.withDefaults(candidate, connection), null, tokens, cost);
    }

    /** Model output wins, draft fills the gaps. */
    private JsonNode merge(JsonNode draft, JsonNode override) {
        ObjectNode merged = Json.object();
        draft.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        override.fields().forEachRemaining(e -> {
            if (e.getValue() != null && !e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        return merged;
    }

    private List<Map<String, Object>> previousResults(Run run, Step step) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Step other : run.steps()) {
            if (other.ordinal() >= step.ordinal() || other.result() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", other.ordinal());
            item.put("title", other.title());
            item.put("tool", other.toolName());
            item.put("result", other.result());
            out.add(item);
        }
        return out;
    }
}
