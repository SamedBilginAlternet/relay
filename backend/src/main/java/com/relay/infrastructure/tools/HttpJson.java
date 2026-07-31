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
            throw new ToolCallException("HTTP " + response.statusCode() + " from " + hostOf(url) + ": "
                    + truncate(response.body()));
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

    public static class ToolCallException extends RuntimeException {
        public ToolCallException(String message) {
            super(message);
        }
    }
}
