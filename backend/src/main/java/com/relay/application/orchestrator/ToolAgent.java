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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
                    params.tokens(), params.costUsd(), params.premiumCostUsd(), params.model());
        }
        step.params(Json.toMap(params.params()));

        Connection connection = connections.findByProvider(tool.provider()).orElse(null);

        String invented = ungroundedIdentifier(run, step, tool, params.params(), connection);
        if (invented != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, invented);
            return StepOutcome.failed(invented, params.tokens(), params.costUsd(),
                    params.premiumCostUsd(), params.model());
        }

        String empty = emptyContent(tool, params.params());
        if (empty != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, empty);
            return StepOutcome.failed(empty, params.tokens(), params.costUsd(),
                    params.premiumCostUsd(), params.model());
        }

        String placeholder = unresolvedPlaceholder(tool, params.params());
        if (placeholder != null) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR, placeholder);
            return StepOutcome.failed(placeholder, params.tokens(), params.costUsd(),
                    params.premiumCostUsd(), params.model());
        }

        journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                tool.name() + " çağrılıyor: " + Json.preview(step.params(), 240));

        ToolResult result;
        try {
            result = tool.execute(params.params(), connection);
        } catch (RuntimeException e) {
            return StepOutcome.failed(tool.name() + " threw: " + e.getMessage(),
                    params.tokens(), params.costUsd(), params.premiumCostUsd(), params.model());
        }

        if (!result.ok()) {
            journal.say(run, step.id(), agent, AgentRole.COORDINATOR,
                    tool.name() + " hata verdi: " + result.error());
            return StepOutcome.failed(result.error(), params.tokens(), params.costUsd(),
                    params.premiumCostUsd(), params.model());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool.name());
        payload.put("mode", result.mode());
        payload.put("durationMs", result.durationMs());
        payload.put("data", Json.toPlain(result.data()));

        journal.say(run, step.id(), agent, AgentRole.VERIFIER,
                tool.name() + " tamam (" + result.durationMs() + " ms), sonuç doğrulamaya gidiyor.");
        return StepOutcome.ok(payload, params.tokens(), params.costUsd(),
                params.premiumCostUsd(), params.model());
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
        return StepOutcome.ok(payload, response);
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
                            String name = candidate.name().toLowerCase(Locale.ROOT);
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
            if (!HUMAN_TEXT_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) || !field.getValue().isTextual()) {
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
                .toLowerCase(Locale.ROOT);

        var fields = params.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!isIdentifier(field.getKey()) || !field.getValue().isTextual()) {
                continue;
            }
            String value = field.getValue().asText().trim();
            // A value with a space in it used to be waved through untested. Whether something
            // is a record key is decided by the field's *name*, not by how it is spelled, so
            // "issueKey": "KAN 42" is exactly as much of a claim about an existing record as
            // "KAN-42" is — and was the one nobody checked.
            if (value.isEmpty() || mentions(haystack, value)) {
                continue;
            }
            return tool.name() + " için " + UNGROUNDED + ": " + field.getKey() + "=" + value
                    + ". Bu kayıt ne hedefte ne de önceki adımların sonucunda geçiyor —"
                    + " önce onu bulan bir arama adımı gerekiyor.";
        }
        return null;
    }

    /**
     * Where a container's real value lives on the connection, per parameter name.
     *
     * <p>Only same-concept mappings belong here. {@code owner} is deliberately not filled
     * from {@code login}: an owner may be an organisation, and swapping in the connected
     * account would be another guess wearing the clothes of a default.
     */
    private static final Map<String, List<String>> CONTAINER_DEFAULTS = Map.of(
            "projectkey", List.of("projectKey", "defaultProject"),
            "project", List.of("projectKey", "defaultProject"),
            "channel", List.of("defaultChannel"),
            "channelid", List.of("defaultChannel"),
            "repo", List.of("repo", "defaultRepo"),
            "repository", List.of("repo", "defaultRepo"),
            "parentdatabaseid", List.of("parentDatabaseId", "defaultDatabaseId"),
            "spreadsheetid", List.of("defaultSpreadsheetId"),
            "sheetname", List.of("defaultSheetName"),
            "pageid", List.of("defaultPageId"));

    /** One field the model addressed wrongly, and what was done about it. */
    private record Grounding(JsonNode params, String note) {
    }

    /**
     * Replaces a container the model invented with the one the user configured.
     *
     * <p>A run kept posting to {@code #genel}, then {@code C046F7R6UE9}, then {@code
     * #general} — three plausible inventions, three {@code channel_not_found}s — while
     * {@code #all-samed} sat configured on the connection. The same hole was open one field
     * over: a run started from chat could file a Jira record under an invented {@code
     * projectKey} while the connection knew the real one. Unlike a record key, a container
     * has a safe answer to fall back to, so this corrects rather than refuses.
     *
     * <p>Three sources, in order: the tool's own defaults, then the connection setting that
     * names the same thing, then — when neither can help — the model's value is put back.
     * Blanking a field nobody can fill would turn a wrong destination into no destination,
     * and the provider's "repo not found" says more than a malformed URL does.
     *
     * @return the correction and the line to journal, or {@code null} when nothing needed it
     */
    private Grounding groundContainers(Run run, Step step, Tool tool, JsonNode params, Connection connection) {
        if (!params.isObject()) {
            return null;
        }
        String haystack = (run.goal() + " " + Json.preview(previousResults(run, step), 4000)
                + " " + (connection == null ? "" : String.join(" ", connection.config().values())))
                .toLowerCase(Locale.ROOT);

        ObjectNode corrected = ((ObjectNode) params).deepCopy();
        List<String> suspect = new ArrayList<>();
        var fields = params.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!CONTAINER_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) || !field.getValue().isTextual()) {
                continue;
            }
            String value = field.getValue().asText().trim();
            if (value.isEmpty() || mentions(haystack, value)) {
                continue;
            }
            corrected.put(field.getKey(), "");
            suspect.add(field.getKey());
        }
        if (suspect.isEmpty()) {
            return null;
        }

        JsonNode filled = tool.withDefaults(corrected, connection);
        ObjectNode out = filled.isObject() ? ((ObjectNode) filled).deepCopy() : corrected;
        List<String> notes = new ArrayList<>();
        for (String name : suspect) {
            String was = params.path(name).asText("").trim();
            String now = out.path(name).asText("").trim();
            if (now.isEmpty()) {
                now = configured(connection, name);
            }
            if (now == null || now.isEmpty()) {
                out.put(name, was);
                continue;
            }
            out.put(name, now);
            notes.add(name + " doğrulanamadı (" + was + "), bağlantıdaki varsayılana çevrildi: " + now);
        }
        return notes.isEmpty() ? null : new Grounding(out, String.join(" · ", notes));
    }

    /**
     * Fills a required container the model left out with the one the user configured.
     *
     * <p>{@link #groundContainers} replaces a container the model got <em>wrong</em>; it walks
     * the fields that are there and skips the blank ones, and it runs after the schema check.
     * So the case it never covered was the field being absent altogether — which is the case
     * that happened: {@code projectKey = KAN} sat on the Jira connection while the run failed
     * on {@code $.projectKey is required} and asked a person to approve the draft that would
     * fail. The reasoning is the same one already written for {@code #genel} / {@code
     * #all-samed}: a container has a safe answer to fall back to, and it is not an invention —
     * it is the value the user set.
     *
     * <p>Deliberately only the fields the schema marks <b>required</b>. An absent optional
     * container is a choice ("search everywhere"), and filling it would quietly narrow a query
     * the user never scoped; an absent required one is not a choice, because without it there
     * is no call to make.
     *
     * <p>And deliberately only containers. {@code summary}, {@code text}, {@code body} are the
     * work itself — see {@code Filler.looksLikeFiller} and {@code RunService.WRITTEN_FROM_THE_MAIL}.
     * If the specialist could not write one, that is a failure and has to read as one.
     */
    private static JsonNode withConfiguredContainers(Tool tool, JsonNode params, Connection connection) {
        if (connection == null || !params.isObject()) {
            return params;
        }
        ObjectNode out = null;
        for (JsonNode required : tool.schema().path("required")) {
            String field = required.asText("");
            if (!CONTAINER_FIELDS.contains(field.toLowerCase(Locale.ROOT))
                    || !params.path(field).asText("").isBlank()) {
                continue;
            }
            String value = configured(connection, field);
            if (value == null) {
                continue;
            }
            if (out == null) {
                out = ((ObjectNode) params).deepCopy();
            }
            out.put(field, value);
        }
        return out == null ? params : out;
    }

    /** The connection's own value for a container field, or {@code null} when it has none. */
    private static String configured(Connection connection, String field) {
        if (connection == null) {
            return null;
        }
        for (String key : CONTAINER_DEFAULTS.getOrDefault(field.toLowerCase(Locale.ROOT), List.of())) {
            String value = connection.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Fields naming a <em>container</em> to write into rather than a record to change:
     * a project to create an issue in, a channel to post to, a repository to comment under,
     * a Notion database to open a page in, a spreadsheet and the tab inside it to append to.
     * Those come from connection defaults or from the user's own setup, so demanding that the
     * goal mention them would block ordinary work. Getting one wrong costs a 400, not a
     * stranger's issue.
     *
     * <p>{@code parentDatabaseId}, {@code spreadsheetId} and {@code pageId} have to be named
     * here rather than left to {@link #isIdentifier}'s "ends with id" rule. By that rule they
     * read as pointers at one existing record — and a Notion id is a 32-character uuid, a
     * spreadsheet id a 44-character token out of a URL, neither of which anybody types into a
     * goal. Every page creation, every appended row and every appended note would be refused
     * as ungrounded. They are containers: the database the page opens in, the file the row
     * goes into, the log page the note lands on — not the record being changed. {@code
     * pageId} earns the label the same way the other two did: Notion has no reading tool, so
     * the only honest sources for it are the goal and the connection's {@code defaultPageId},
     * and a page is a container of blocks exactly as a database is a container of pages.
     */
    private static final java.util.Set<String> CONTAINER_FIELDS = java.util.Set.of(
            "projectkey", "project", "repo", "repository", "owner", "channel", "channelid",
            "parentdatabaseid", "spreadsheetid", "sheetname", "pageid");

    /**
     * Did the run really see this value, as a value — or does it just happen to sit inside a
     * longer one?
     *
     * <p>{@code contains} answered the second question and called it the first. A previous
     * step that returned {@code KAN-10} therefore vouched for {@code KAN-1}, and the gate
     * that exists to stop Relay closing a stranger's record opened on the exact case it was
     * built for: an issue that exists, but is the wrong one. Nothing failed loudly — the
     * write succeeded, on somebody else's ticket.
     *
     * <p>The boundary excludes {@code -} and {@code _} as well as word characters, because
     * that is what record keys are made of: {@code KAN-1} must not match inside
     * {@code KAN-10}, and {@code PR-7} must not match inside {@code PR-77}. A provider
     * specific pattern ({@code [A-Z]+-\d+} and so on) would be more precise and would have
     * to be maintained per provider for ever; a boundary is provider independent, and being
     * wrong here costs a lookup step rather than a stranger's record.
     */
    private static boolean mentions(String haystack, String value) {
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(value.toLowerCase(Locale.ROOT)) + "(?![\\w-])")
                .matcher(haystack)
                .find();
    }

    /** Names that point at one specific, already existing record. */
    private static boolean isIdentifier(String field) {
        String name = field.toLowerCase(Locale.ROOT);
        if (CONTAINER_FIELDS.contains(name)) {
            return false;
        }
        return name.endsWith("key") || name.endsWith("id") || name.endsWith("number");
    }

    // ---- parameters -------------------------------------------------------

    /**
     * What one out-of-band parameter refresh cost, and whether it produced anything sendable.
     *
     * <p>{@code error} carries the sentence to show when it did not. It used to be a bare
     * {@code ok} that every caller ignored: the coordinator has to be able to say <em>which</em>
     * fields are missing, and a boolean cannot.
     */
    public record ParamRefresh(boolean ok, String error, long tokens, double costUsd,
                               Double premiumCostUsd, String model) {
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
            return new ParamRefresh(false, "tanımsız araç: " + step.toolName(), 0, 0, null, null);
        }
        // The step is on its way back to the gate, so the human's earlier correction has had
        // its turn and lost — the provider refused it. Releasing the lock here lets the
        // specialist propose something new, which the human sees and may correct again;
        // keeping it would park the step forever on a value already known to be rejected.
        step.paramsLocked(false);
        ParamOutcome outcome = finaliseParams(run, step, tool);
        if (outcome.valid()) {
            step.params(Json.toMap(outcome.params()));
        }
        return new ParamRefresh(outcome.valid(), outcome.valid() ? null : incomplete(tool, outcome.error()),
                outcome.tokens(), outcome.costUsd(), outcome.premiumCostUsd(), outcome.model());
    }

    /**
     * A schema failure said to the person who would otherwise have been asked about it.
     *
     * <p>The validator's own words are kept — {@code $.projectKey is required} names the field,
     * and that is the whole point of showing it — with a Turkish sentence around them saying
     * what it means for the run.
     */
    private static String incomplete(Tool tool, String error) {
        return tool.name() + " için parametreler eksik: " + error
                + ". Bu hâliyle sağlayıcıya gönderilemez, onaya da sunulmadı.";
    }

    /**
     * Whether these parameters can be put in front of a person at all.
     *
     * <p>Both gates below already ran at call time, which is one gate too late: live, a user
     * was asked to approve the Slack message <em>"KAN projesinde
     * {@code {{steps[0].result.issues.length}}} adet açık kayıt vardır."</em> The message
     * never reached Slack — the gate did its job — but the person had already spent their
     * attention deciding about a sentence that was never going to be sent, and approving it
     * bought another model round for nothing. The address check was moved in front of the
     * approval for exactly this reason ("onaylanan parametre ile gönderilen parametre aynı
     * olmalı"); these two belong there with it.
     *
     * <p>The checks at call time stay. A second look costs nothing and closes the gap for
     * steps that never pass a gate at all.
     *
     * @return the message explaining what is not presentable, or {@code null} when it is
     */
    public String unpresentable(Step step) {
        Tool tool = tools.find(step.toolName()).orElse(null);
        if (tool == null) {
            return null;
        }
        JsonNode params = Json.toNode(step.params());
        if (!params.isObject()) {
            return null;
        }
        String placeholder = unresolvedPlaceholder(tool, params);
        return placeholder != null ? placeholder : emptyContent(tool, params);
    }

    /**
     * The values a human typed at the gate that cannot be sent, one sentence per field.
     *
     * <p>Only the placeholder markers, not the filler phrases: a person who writes "TODO:"
     * into a message means it, and refusing their own words would be Relay marking its user's
     * homework. {@code {{steps[0].summary}}} is different — it is not a sentence, it is a
     * substitution nobody is going to perform, and it was accepted with a 200 and answered a
     * model round later.
     *
     * @return field name to explanation; empty when everything can be sent
     */
    public static Map<String, String> unsendableValues(Map<String, Object> params) {
        Map<String, String> problems = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (value instanceof String text && com.relay.application.text.Placeholder.unresolved(text)) {
                problems.put(key, "Bu değer bir yer tutucu içeriyor ({{…}}, ${…}, steps[…]) —"
                        + " Relay'de şablon çözümlemesi yok, değeri olduğu gibi yaz.");
            }
        });
        return problems;
    }

    private record ParamOutcome(boolean valid, JsonNode params, String error, long tokens, double costUsd,
                                Double premiumCostUsd, String model) {

        ParamOutcome(boolean valid, JsonNode params, String error, long tokens, double costUsd) {
            this(valid, params, error, tokens, costUsd, null, null);
        }
    }


    private ParamOutcome finaliseParams(Run run, Step step, Tool tool) {
        JsonNode draft = Json.toNode(step.params());
        if (!draft.isObject()) {
            draft = Json.object();
        }
        if (step.paramsLocked()) {
            // A person read these values, changed them and pressed Onayla. Three things
            // below would quietly undo that: the defaults, the model turn a filled-in
            // lastProviderError forces, and the address correction — which replaces any
            // channel the goal never mentions, and the goal never mentions the one the user
            // just typed. So the edit goes to the provider exactly as it was on the screen.
            // The guards in execute() still see it: an edit is trusted, not exempt, and a
            // person who pastes {{steps[3].channel}} into the box is stopped like anyone.
            SchemaValidator.Result edited = SchemaValidator.validate(tool.schema(), draft);
            return new ParamOutcome(edited.valid(), draft, edited.valid() ? null : edited.message(), 0, 0);
        }

        // Before the model sees the draft: what the user configured beats what a model would
        // invent. A blank channel filled in from the connection is also one fewer model call.
        Connection connection = connections.findByProvider(tool.provider()).orElse(null);
        draft = withConfiguredContainers(tool, tool.withDefaults(draft, connection), connection);

        long tokens = 0;
        double cost = 0;
        // Null, not zero: a draft that needed no model call has no premium to compare with,
        // and zero there would read as "the strong model would have been free".
        Double premium = null;
        String model = null;
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
                            + settings(connections.findByProvider(tool.provider()).orElse(null))
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
            premium = response.premiumCostUsd();
            model = response.model();
            JsonNode fromModel = Json.extract(response.content());
            if (fromModel != null && fromModel.isObject()) {
                candidate = merge(draft, fromModel);
            }
        }

        // Again after the model turn, not only on the draft: a model that answers with the
        // container blanked out ("projectKey": "") would otherwise undo the fill and fail the
        // check for a field the connection can answer.
        candidate = withConfiguredContainers(tool, candidate, connection);

        SchemaValidator.Result check = SchemaValidator.validate(tool.schema(), candidate);
        if (!check.valid()) {
            return new ParamOutcome(false, candidate, check.message(), tokens, cost, premium, model);
        }
        // Applied again in case the model overwrote a resolved value with a placeholder.
        // Defaults land here rather than at call time so the parameters a human approves are
        // the parameters that get sent — which is also why the address check belongs here:
        // a channel silently corrected after approval would be a different message than the
        // one shown.
        JsonNode finalised = tool.withDefaults(candidate, connection);
        Grounding grounded = groundContainers(run, step, tool, finalised, connection);
        if (grounded != null) {
            journal.say(run, step.id(), AgentRole.toolAgent(tool.name()), AgentRole.COORDINATOR,
                    grounded.note());
            finalised = grounded.params();
        }
        return new ParamOutcome(true, finalised, null, tokens, cost, premium, model);
    }

    /** Config keys that describe *where* to work. Never a credential — see {@link #settings}. */
    private static final java.util.Set<String> SETTING_FIELDS = java.util.Set.of(
            "defaultchannel", "projectkey", "defaultproject", "login", "baseurl", "repo");

    /**
     * The user's own configuration, handed to the specialist.
     *
     * <p>Live, a run posted to {@code #general} while {@code #all-samed} sat configured on
     * the connection: the model had no way to know, so it guessed a channel that does not
     * exist and Slack answered {@code channel_not_found}. Defaults only cover a blank or
     * placeholder value — a confident wrong guess slips past them.
     *
     * <p>Strictly allow-listed: tokens and secrets never enter a prompt, so this reads from
     * a fixed set of field names rather than filtering by what looks sensitive.
     */
    private static String settings(Connection connection) {
        if (connection == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        connection.config().forEach((key, value) -> {
            if (SETTING_FIELDS.contains(key.toLowerCase(Locale.ROOT)) && value != null && !value.isBlank()) {
                sb.append("- ").append(key).append(" = ").append(value.trim()).append('\n');
            }
        });
        return sb.length() == 0 ? ""
                : "\n\nUSER SETTINGS for this provider — prefer these over your own guess:\n" + sb;
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
