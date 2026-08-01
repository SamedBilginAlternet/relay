package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON-over-HTTP helper for the live tool implementations. 15s timeout (ARCHITECTURE §8). */
public final class HttpJson {

    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpJson() {
    }

    public static JsonNode send(String method, String url, Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        headers.forEach(builder::header);

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, publisher);

        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(response.statusCode(), hostOf(url), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return Json.object();
        }
        return Json.parse(response.body());
    }

    public static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "provider";
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 400 ? body.substring(0, 400) + "…" : body;
    }

    /**
     * Turns a non-2xx response into the exception the tools throw.
     *
     * <p>The status and the raw body travel on the exception so a provider-aware tool can
     * rewrite the message into something a person can act on. What travels in the
     * <em>message</em> is a different matter: a 401/403 body is the one place a provider
     * happily echoes back the credential it just rejected, so that body never reaches the
     * message, and every other body goes through {@link #redact}.
     */
    static ToolCallException failure(int status, String host, String body) {
        String message = status == 401 || status == 403
                ? "HTTP " + status + " from " + host + ": kimlik doğrulama reddedildi"
                : "HTTP " + status + " from " + host + ": " + redact(truncate(body));
        return new ToolCallException(message, status, body);
    }

    /**
     * Blanks out anything shaped like a credential.
     *
     * <p>An error body is quoted into the run timeline and into the log, and providers do
     * put tokens in them (an echoed {@code Authorization} header, a signed URL). One regex
     * per issuer beats a guess at "long random-looking string", which would also eat issue
     * keys and message ids.
     */
    static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String out = text;
        for (java.util.regex.Pattern pattern : SECRET_SHAPES) {
            out = pattern.matcher(out).replaceAll("****");
        }
        return out;
    }

    private static final java.util.List<java.util.regex.Pattern> SECRET_SHAPES = java.util.List.of(
            // Atlassian API token / OAuth, Slack bot+user tokens, Google access + refresh
            // tokens, GitHub PATs, Notion integration tokens, Groq keys, and any
            // Authorization header echoed back.
            java.util.regex.Pattern.compile("ATATT[A-Za-z0-9_.\\-=]{8,}"),
            java.util.regex.Pattern.compile("xox[abprs]-[A-Za-z0-9-]{8,}"),
            java.util.regex.Pattern.compile("ya29\\.[A-Za-z0-9_\\-]{8,}"),
            java.util.regex.Pattern.compile("1//[A-Za-z0-9_\\-]{10,}"),
            java.util.regex.Pattern.compile("gh[pousr]_[A-Za-z0-9]{8,}"),
            java.util.regex.Pattern.compile("ntn_[A-Za-z0-9]{8,}"),
            java.util.regex.Pattern.compile("gsk_[A-Za-z0-9]{8,}"),
            java.util.regex.Pattern.compile("(?i)(?:basic|bearer)\\s+[A-Za-z0-9+/=_.\\-]{8,}"));

    public static class ToolCallException extends RuntimeException {

        private final int status;
        private final String body;

        public ToolCallException(String message) {
            this(message, 0, null);
        }

        public ToolCallException(String message, int status, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }

        /** HTTP status that caused this, or 0 when the failure was not an HTTP response. */
        public int status() {
            return status;
        }

        /** The provider's raw response body — for rewriting, never for showing as-is. */
        public String body() {
            return body;
        }
    }
}
