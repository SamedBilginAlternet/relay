package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opens a Google Docs document — the meeting note, the write-up, the page a knowledge
 * worker actually hands to someone.
 *
 * <p>Relay's Google writes cover the mail (draft), the calendar (event) and the spreadsheet
 * (row); the document was the missing one, and it is the shape most of a desk's output
 * takes. The {@code google} connection, the OAuth seam and the reconsent degradation all
 * exist — only the grant was one scope short ({@link GoogleOAuth#DOCUMENTS_SCOPE}, which is
 * sensitive, not restricted: no CASA).
 *
 * <p>ONE TOOL, TWO CALLS, SAID OUT LOUD. The Docs API cannot create a document with content
 * in it: {@code documents.create} takes a title and nothing else, and the text goes in with
 * a second call ({@code :batchUpdate} / {@code insertText}). Both outcomes are reported in
 * the result — {@code contentInserted} is the flag a reader trusts — and the seam between
 * them is where the idempotency decision lives, on {@link #call}.
 *
 * <p>Deliberately not in the brief's {@code SECTIONS}: a write costs ~100 tokens on the
 * runs that use it and nothing on the ones that do not.
 */
@Component
public class DocsCreateDocumentTool extends GoogleTool {

    /** The one API this tool talks to: create, and one insert into what it just created. */
    static final String DOCS = "https://docs.googleapis.com/v1/documents";

    /**
     * What a token issued before {@code documents} is told. It names the screen and the
     * reason, because Google's "insufficient authentication scopes" names neither.
     */
    static final String NEEDS_CONSENT =
            "Google izni doküman oluşturmayı kapsamıyor; Bağlantılar'dan Google'a yeniden "
            + "bağlan (yeni izin: dokümanlar). Mevcut bağlantın okuma işlerini, mail "
            + "taslaklarını, takvimi ve tabloyu yapmaya devam ediyor.";

    public DocsCreateDocumentTool(@Value("${app.tools.mode:replay}") String mode,
                                  FixtureStore fixtures, GoogleOAuth oauth) {
        super(ToolsMode.parse(mode), fixtures, oauth);
    }

    @Override
    public String name() {
        return "docs.createDocument";
    }

    @Override
    public String description() {
        return "Create a Google Docs document with a title and body text. Use it to open a "
                + "meeting-note draft or write a result out as a document people can edit. "
                + "content is plain text. Requires approval.";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.WRITE;
    }

    /**
     * No container here: unlike a row or a Notion page, a new document needs no parent the
     * user configured — Docs puts it in the account's own Drive root. {@code title} and
     * {@code content} are the work itself; nothing defaults them, and the Filler gate holds
     * {@code content} the way it holds a Slack message.
     */
    @Override
    public JsonNode schema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.putArray("required").add("title").add("content");
        ObjectNode props = schema.putObject("properties");
        ObjectNode title = props.putObject("title");
        title.put("type", "string");
        title.put("minLength", 1);
        title.put("description", "One line title of the document");
        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("minLength", 1);
        content.put("description", "Body text, plain text. Newlines are kept; markdown is "
                + "not converted");
        return schema;
    }

    /**
     * Create, then insert — and if the insert fails, the step still succeeds.
     *
     * <p>That is the idempotency decision, and it is deliberate. A failed step is retried:
     * the verifier sends a step back up to two times, and every retry of a step that
     * failed <em>after</em> {@code documents.create} succeeded would open another document
     * with the same title — three empty twins in the user's Drive, which is strictly worse
     * than the thing being reported. The write the human approved (this document, under
     * this title) exists; what did not happen is said in the result, out loud:
     * {@code contentInserted: false} plus a sentence naming the link where the text has to
     * be pasted by hand. Honest partial success over a retry loop that multiplies files.
     */
    @Override
    protected JsonNode call(JsonNode params, Connection connection) throws Exception {
        if (!GoogleOAuth.granted(connection, GoogleOAuth.DOCUMENTS_SCOPE)) {
            throw new HttpJson.ToolCallException(NEEDS_CONSENT);
        }
        String title = oneLine(params.path("title").asText(""));
        String content = params.path("content").asText("");

        ObjectNode createBody = Json.object();
        createBody.put("title", title);
        JsonNode created;
        try {
            created = create(DOCS, headers(connection), createBody);
        } catch (HttpJson.ToolCallException e) {
            throw explain(e);
        }
        String docId = created.path("documentId").asText("");
        String url = "https://docs.google.com/document/d/" + docId + "/edit";

        boolean inserted = false;
        String problem = null;
        ObjectNode insertBody = Json.object();
        ObjectNode insertText = insertBody.putArray("requests").addObject().putObject("insertText");
        insertText.putObject("location").put("index", 1);
        insertText.put("text", content);
        try {
            insert(DOCS + "/" + HttpJson.encode(docId) + ":batchUpdate",
                    headers(connection), insertBody);
            inserted = true;
        } catch (HttpJson.ToolCallException e) {
            problem = AbstractTool.describe(e);
        }

        ObjectNode out = Json.object();
        out.put("docId", docId);
        out.put("title", created.path("title").asText(title));
        out.put("url", url);
        out.put("contentInserted", inserted);
        if (!inserted) {
            out.put("note", "Doküman oluşturuldu ama içerik yazılamadı — sayfa şu an boş. "
                    + "Metni " + url + " adresine elle yapıştırabilirsin. (İçerik hatası: "
                    + (problem == null ? "bilinmiyor" : problem) + ") Adım yeniden denenirse "
                    + "aynı başlıkla ikinci bir doküman açılır; bu yüzden adım burada "
                    + "başarısız sayılmadı.");
        }
        return out;
    }

    /**
     * The two network calls, isolated so a test can watch them: one POST that creates a
     * document, one POST that inserts text into the document just created. Everything that
     * would let this tool touch an existing file — a GET, a document id from outside this
     * call — would have to be added here, and a test asserts it never is.
     */
    JsonNode create(String url, Map<String, String> headers, JsonNode body) throws Exception {
        return HttpJson.send("POST", url, headers, body);
    }

    JsonNode insert(String url, Map<String, String> headers, JsonNode body) throws Exception {
        return HttpJson.send("POST", url, headers, body);
    }

    /** {@link #call} builds the reply itself; Google's revision ids and body tree never leave. */
    @Override
    protected JsonNode project(JsonNode raw) {
        return raw;
    }

    /**
     * Google answers a token that predates {@code documents} with 401/403 and "insufficient
     * authentication scopes" — the same problem the pre-flight check catches, reached by a
     * different road, so it gets the same sentence. A 403 that is not about scope is almost
     * always the Docs API switched off in the console, and the sentence says so instead of
     * posing as a credential problem. The provider's body is never repeated.
     */
    private static RuntimeException explain(HttpJson.ToolCallException failure) {
        int status = failure.status();
        String body = failure.body() == null ? "" : failure.body().toLowerCase(Locale.ROOT);
        if ((status == 401 || status == 403)
                && (body.contains("insufficient") || body.contains("scope"))) {
            return new HttpJson.ToolCallException(NEEDS_CONSENT, status, failure.body());
        }
        if (status == 401 || status == 403) {
            return new HttpJson.ToolCallException("Google dokümanı reddetti (HTTP " + status
                    + "). Google Cloud Console'da Google Docs API'nin etkin olduğunu kontrol "
                    + "et; Bağlantılar'dan Google'a yeniden bağlanmayı da dene.",
                    status, failure.body());
        }
        return failure;
    }

    /** A document title is one line, for the same reason a calendar summary is. */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
