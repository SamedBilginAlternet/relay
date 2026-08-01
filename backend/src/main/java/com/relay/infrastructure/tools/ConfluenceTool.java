package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.text.Placeholder;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confluence Cloud — the {@code /wiki} of the Atlassian site Relay is already connected to.
 *
 * <p>WHY THERE IS NO CONFLUENCE CONNECTION CARD. The credentials are the {@code jira}
 * connection's, verbatim: same {@code baseUrl}, same {@code email}, same {@code apiToken} —
 * an Atlassian API token is issued to the <em>account</em>, not to a product, so the one the
 * user pasted for Jira already opens Confluence on the same site. A second card would ask
 * for the same secret twice and store it twice. The precedent is {@code google}: one
 * connection carries {@code gmail.*}, {@code calendar.*} and {@code sheets.*} as separate
 * tool namespaces, so {@code confluence.*} rides {@code jira} the same way — hence the
 * {@link #provider()} override, exactly like {@code GoogleTool}'s.
 *
 * <p>WHY v2 AND NOT v1. {@code POST /wiki/rest/api/content} would take the space <em>key</em>
 * directly, but Atlassian has deprecated the v1 content endpoints and has form on actually
 * removing them (the v1 search endpoint answered HTTP 410 mid-hackathon — see
 * INTEGRATIONS.md §2). {@code POST /wiki/api/v2/pages} is the current API and wants a
 * numeric space <em>id</em> nobody knows by heart, so the tool spends one extra GET turning
 * the key the user does know into the id the endpoint wants. Two calls, one write — and the
 * read touches nothing but the space's own id.
 *
 * <p>Like Notion, this namespace is WRITE-only and out of the brief on purpose: a reading
 * tool there costs two model turns on every refresh, a write costs ~100 tokens on the runs
 * that use it and nothing on the ones that do not.
 */
public abstract class ConfluenceTool extends AbstractTool {

    protected ConfluenceTool(ToolsMode mode, FixtureStore fixtures) {
        super(mode, fixtures);
    }

    /** The credentials live on the jira connection — see the class comment. */
    @Override
    public String provider() {
        return "jira";
    }

    @Override
    protected boolean usable(Connection connection) {
        return notBlank(connection.get("baseUrl")) && notBlank(connection.get("email"))
                && notBlank(connection.get("apiToken"));
    }

    protected Map<String, String> headers(Connection connection) {
        String basic = Base64.getEncoder().encodeToString(
                (connection.get("email") + ":" + connection.get("apiToken"))
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Basic " + basic);
        return headers;
    }

    /**
     * The Confluence root, derived from the Jira {@code baseUrl} — never asked for.
     *
     * <p>The connection stores the Atlassian site root ({@code https://sirket.atlassian.net});
     * Confluence Cloud lives under its {@code /wiki}. Asking the user for a second URL would
     * be asking them to type a string this method can compute, and the day they typed it with
     * a stray {@code /wiki} already on it the two would fight — so a base that already ends in
     * {@code /wiki} is kept as it is.
     */
    static String wikiBase(Connection connection) {
        String url = connection.get("baseUrl").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/wiki") ? url : url + "/wiki";
    }

    /** Sends a Confluence request and rewrites a rejection into a sentence a person can act on. */
    protected static JsonNode confluence(String method, String url, Map<String, String> headers,
                                         Object body) throws Exception {
        try {
            return HttpJson.send(method, url, headers, body);
        } catch (HttpJson.ToolCallException e) {
            if (e.status() == 0) {
                throw e;
            }
            throw new HttpJson.ToolCallException(explain(e.status(), e.body()), e.status(), e.body());
        }
    }

    /**
     * The human sentence for one Confluence rejection. Never contains the raw body.
     *
     * <p>403 is the one that has to be honest rather than generic: on an Atlassian site the
     * token itself is almost never the problem (it is the same token Jira accepts a step
     * earlier), the <em>product</em> is — Confluence not enabled on the site, or a space the
     * account cannot enter. Jira's "e-posta ve API token'ı kontrol edin" would send the
     * reader off to re-paste a credential that is fine.
     */
    static String explain(int status, String body) {
        if (status == 401) {
            return "Confluence kimlik doğrulaması reddedildi (HTTP 401). Bağlantı "
                    + "ayarlarındaki e-posta ve API token'ı kontrol edin — Confluence, Jira "
                    + "ile aynı hesabı kullanır.";
        }
        if (status == 403) {
            return "Confluence isteği geri çevirdi (HTTP 403). Bu genellikle token değil "
                    + "erişim sorunudur: Confluence bu sitede açık olmayabilir ya da bu "
                    + "hesabın hedef alana (space) erişimi olmayabilir. Siteyi tarayıcıda "
                    + "aynı hesapla açıp /wiki adresini görebildiğini kontrol et.";
        }
        if (status == 404) {
            return "Confluence hedefi bulamadı (HTTP 404). Site adresinin altında Confluence "
                    + "kurulu olmayabilir (baseUrl + /wiki) ya da alan bu hesapla "
                    + "paylaşılmamış olabilir.";
        }
        String reason = reason(body);
        String head = switch (status) {
            case 400 -> "Confluence isteği reddetti (HTTP 400)";
            case 429 -> "Confluence istek sınırına takıldı (HTTP 429), biraz sonra tekrar deneyin";
            default -> "Confluence isteği reddetti (HTTP " + status + ")";
        };
        return reason.isBlank() ? head + "." : head + ": " + reason;
    }

    /** The v2 error envelope's own sentences ({@code errors[].title}), redacted like any quote. */
    private static String reason(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode error : parsed.path("errors")) {
            String title = error.path("title").asText("").trim();
            if (!title.isEmpty()) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(HttpJson.redact(title));
            }
        }
        return out.toString();
    }

    /**
     * Plain text as Confluence storage format, honestly.
     *
     * <p>Storage format is XHTML, and this wraps rather than converts: every line becomes a
     * {@code <p>} with its characters XML-escaped, so a line that says {@code **bold**} lands
     * on the page saying {@code **bold**}. Pretending to translate markdown this tool cannot
     * fully translate would put half-rendered markup in front of the person the page was
     * written for — verbatim text is worth more than a wrong rendering. The escaping is the
     * security half of the same promise: a model-written {@code <script>} arrives as text,
     * never as markup.
     */
    static String storage(String content) {
        StringBuilder out = new StringBuilder();
        for (String line : (content == null ? "" : content).split("\r?\n")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            out.append("<p>").append(escape(text)).append("</p>");
        }
        if (out.length() == 0) {
            out.append("<p>").append(escape((content == null ? "" : content).strip())).append("</p>");
        }
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ------------------------------------------------------------- createPage

    /**
     * Opens a page in a Confluence space — where the teams that track work in Jira keep the
     * words about it: the decision, the runbook, the meeting note that outlives the sprint.
     */
    @Component
    public static class CreatePage extends ConfluenceTool {

        public CreatePage(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "confluence.createPage";
        }

        @Override
        public String description() {
            return "Create a Confluence page in a space, with a title and body text. It uses "
                    + "the same Atlassian account as Jira. Use it to write a decision, a "
                    + "summary or documentation next to the team's wiki. Requires approval "
                    + "by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        /**
         * {@code spaceKey} is required for the reason {@code projectKey} is on
         * {@code jira.createIssue}: the container is not a choice, and being in
         * {@code required} is what lets {@code ToolAgent.CONTAINER_DEFAULTS} fill it from
         * the connection's {@code defaultSpaceKey} when the model leaves it out. {@code
         * title} and {@code content} are the work itself — nothing fills them.
         */
        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("spaceKey").add("title").add("content");
            ObjectNode props = schema.putObject("properties");
            ObjectNode space = props.putObject("spaceKey");
            space.put("type", "string");
            space.put("description", "Confluence space key, e.g. DOC — omit to use the one "
                    + "configured on the connection");
            ObjectNode title = props.putObject("title");
            title.put("type", "string");
            title.put("minLength", 1);
            title.put("description", "One line title of the page");
            ObjectNode content = props.putObject("content");
            content.put("type", "string");
            content.put("minLength", 1);
            content.put("description", "Body text, plain text. Each line becomes a paragraph; "
                    + "markdown is not converted");
            return schema;
        }

        /**
         * Falls back to the jira connection's {@code defaultSpaceKey} when the space is
         * missing or left as a placeholder — resolved here rather than inside {@link #call},
         * so the approval screen names the space the page will actually land in.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (connection == null || !params.isObject()) {
                return params;
            }
            String space = params.path("spaceKey").asText("");
            if (!space.isBlank() && !Placeholder.unresolved(space)) {
                return params;
            }
            String fallback = connection.get("defaultSpaceKey");
            if (fallback == null || fallback.isBlank()) {
                return params;
            }
            ObjectNode resolved = ((ObjectNode) params).deepCopy();
            resolved.put("spaceKey", fallback.trim());
            return resolved;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String spaceKey = params.path("spaceKey").asText("").trim();
            if (spaceKey.isEmpty()) {
                throw new HttpJson.ToolCallException("Sayfanın hangi Confluence alanında "
                        + "açılacağı belli değil: spaceKey boş ve Jira bağlantısında "
                        + "varsayılan alan anahtarı (defaultSpaceKey) kayıtlı değil.");
            }
            String wiki = wikiBase(connection);

            // The key the user knows, turned into the id the v2 endpoint wants. An empty
            // answer is a wrong key or an unshared space — named as such, because the
            // provider's own 404-shaped emptiness names neither.
            JsonNode spaces = get(wiki + "/api/v2/spaces?keys=" + HttpJson.encode(spaceKey),
                    headers(connection));
            JsonNode found = spaces.path("results");
            if (!found.isArray() || found.isEmpty()) {
                throw new HttpJson.ToolCallException("Confluence'ta \"" + spaceKey + "\" "
                        + "anahtarlı bir alan (space) bulunamadı ya da bu hesap onu göremiyor. "
                        + "Alan anahtarı Confluence adresinde /spaces/<ANAHTAR>/ olarak "
                        + "görünür; Bağlantılar → Jira formundaki defaultSpaceKey ayarını da "
                        + "kontrol et.");
            }
            String spaceId = found.get(0).path("id").asText("");

            ObjectNode body = Json.object();
            body.put("spaceId", spaceId);
            body.put("status", "current");
            body.put("title", params.path("title").asText("").replaceAll("[\\r\\n]+", " ").trim());
            ObjectNode storage = body.putObject("body");
            storage.put("representation", "storage");
            storage.put("value", storage(params.path("content").asText()));

            JsonNode created = post(wiki + "/api/v2/pages", headers(connection), body);

            // The reply is built here, so Confluence's version tree, author objects and
            // `_links` collection never reach the timeline — only where the page is and
            // what it is called.
            ObjectNode out = Json.object();
            out.put("id", created.path("id").asText(""));
            out.put("title", created.path("title").asText(body.path("title").asText("")));
            out.put("spaceKey", spaceKey);
            out.put("url", urlOf(created, wiki, spaceKey));
            out.put("created", true);
            return out;
        }

        /** The page's own address when the answer carries one, the space's when it does not. */
        private static String urlOf(JsonNode created, String wiki, String spaceKey) {
            String webui = created.path("_links").path("webui").asText("");
            if (webui.isEmpty()) {
                return wiki + "/spaces/" + spaceKey;
            }
            String base = created.path("_links").path("base").asText(wiki);
            return (base.isBlank() ? wiki : base) + webui;
        }

        /**
         * The two network calls, isolated so a test can watch them: one GET that resolves a
         * space key to its id, one POST that creates the page. Everything that would let this
         * tool edit or delete a page — a PUT, a DELETE, a different path — would have to be
         * added here, and a test asserts nothing else ever is.
         */
        JsonNode get(String url, Map<String, String> headers) throws Exception {
            return confluence("GET", url, headers, null);
        }

        JsonNode post(String url, Map<String, String> headers, JsonNode body) throws Exception {
            return confluence("POST", url, headers, body);
        }

        /** {@link #call} already builds the reply; saying so is the contract. */
        @Override
        protected JsonNode project(JsonNode raw) {
            return raw;
        }
    }
}
