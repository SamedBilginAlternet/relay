package com.relay.infrastructure.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plain JDK HTTP client. No secrets are ever logged here.
 *
 * <p>Forced to HTTP/1.1. Live, DeepSeek's endpoint (behind CloudFront) answered {@code
 * curl}'s plain GET over HTTP/2 instantly, but every real POST from this client — body,
 * bearer header, the actual shape a completion call takes — failed with a bare transport
 * exception. That split (trivial request fine, real request never completes) is the
 * signature of the JDK HttpClient's own HTTP/2 implementation, which has had long-standing
 * issues with request bodies against exactly this kind of reverse-proxied endpoint. HTTP/1.1
 * has no such history here and every provider in this chain speaks it fine.
 */
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpTransport(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Seconds-form {@code Retry-After} only; the date form is not worth the parsing risk. */
    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .map(String::trim)
                .filter(value -> value.matches("\\d+"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(null);
    }

    @Override
    public Reply post(String url, String apiKey, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), retryAfter(response));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(599, "interrupted");
        } catch (Exception e) {
            return new Reply(599, "transport error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
