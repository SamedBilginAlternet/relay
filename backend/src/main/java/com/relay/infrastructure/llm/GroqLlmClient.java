package com.relay.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;

/**
 * Groq chat completions with multi-key rotation.
 *
 * <p>Keys come from {@code GROQ_API_KEYS} (comma separated). They are used in order;
 * a 429 / quota answer rotates to the next key and parks the failing one for 60s.
 * When every key is cooling down the call throws {@link LlmUnavailableException} and
 * the router drops to the stub.
 */
public class GroqLlmClient implements LlmClient {

    private static final Logger LOG = System.getLogger(GroqLlmClient.class.getName());

    private final ApiKeyPool keys;
    private final HttpTransport transport;
    private final String baseUrl;
    private final String model;
    private final double inputUsdPerMillion;
    private final double outputUsdPerMillion;

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion) {
        this.keys = keys;
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.model = model;
        this.inputUsdPerMillion = inputUsdPerMillion;
        this.outputUsdPerMillion = outputUsdPerMillion;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String body = requestBody(request);
        String lastError = "no key available";

        for (int attempt = 0; attempt < Math.max(1, keys.total()); attempt++) {
            Optional<String> key = keys.next();
            if (key.isEmpty()) {
                break;
            }
            HttpTransport.Reply reply = transport.post(baseUrl + "/chat/completions", key.get(), body);
            if (reply.ok()) {
                return parse(reply.body());
            }
            lastError = "groq HTTP " + reply.status();
            if (reply.shouldRotate()) {
                // A refused key (revoked, out of quota) never recovers; a rate limited one
                // does. Parking both for 60s would keep resurrecting a dead key.
                if (reply.refused()) {
                    keys.retire(key.get());
                    LOG.log(Level.WARNING, "groq key {0} retired ({1}) — provider refused it",
                            ApiKeyPool.mask(key.get()), reply.status());
                } else {
                    keys.penalize(key.get(), reply.retryAfter());
                    LOG.log(Level.WARNING, "groq key {0} parked ({1}, retry-after {2}) — rotating",
                            ApiKeyPool.mask(key.get()), reply.status(), String.valueOf(reply.retryAfter()));
                }
                continue;
            }
            // A genuine bad request (400 with a schema problem) will not be fixed by another key.
            throw new LlmUnavailableException("groq rejected the request: HTTP " + reply.status());
        }
        throw new LlmUnavailableException("all groq keys exhausted (" + lastError + ")");
    }

    @Override
    public String name() {
        return "groq:" + model;
    }

    @Override
    public boolean degraded() {
        return keys.available() == 0;
    }

    public ApiKeyPool keys() {
        return keys;
    }

    public String model() {
        return model;
    }

    // -----------------------------------------------------------------------

    private String requestBody(LlmRequest request) {
        ObjectNode root = Json.object();
        root.put("model", model);
        root.put("temperature", request.temperature());
        root.put("max_tokens", request.maxTokens());

        ArrayNode messages = root.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            String extra = request.schema() == null ? ""
                    : "\n\nAnswer with JSON only, matching this schema:\n" + request.schema();
            system.put("content", request.system() + extra);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.user() == null ? "" : request.user());

        if (request.schema() != null) {
            root.putObject("response_format").put("type", "json_object");
        }
        return root.toString();
    }

    private LlmResponse parse(String body) {
        JsonNode root = Json.parse(body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        long promptTokens = root.path("usage").path("prompt_tokens").asLong(0);
        long completionTokens = root.path("usage").path("completion_tokens").asLong(0);
        double cost = (promptTokens / 1_000_000d) * inputUsdPerMillion
                + (completionTokens / 1_000_000d) * outputUsdPerMillion;
        return new LlmResponse(content, promptTokens, completionTokens, cost, model, false);
    }
}
