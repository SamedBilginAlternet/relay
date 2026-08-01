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
    private final String smallModel;
    /**
     * Cooldowns are tracked per model on purpose: Groq rate limits each model separately,
     * so a key that is out of budget on the big model may still answer on the small one.
     * Sharing one pool would park it for both and throw that capacity away.
     */
    private final ApiKeyPool smallKeys;

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion) {
        this(keys, transport, baseUrl, model, inputUsdPerMillion, outputUsdPerMillion, null, null);
    }

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion,
                         String smallModel, ApiKeyPool smallKeys) {
        this.keys = keys;
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.model = model;
        this.inputUsdPerMillion = inputUsdPerMillion;
        this.outputUsdPerMillion = outputUsdPerMillion;
        this.smallModel = smallModel == null || smallModel.isBlank() ? null : smallModel.trim();
        this.smallKeys = smallKeys;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Attempt big = attempt(request, model, keys);
        if (big.response() != null) {
            return big.response();
        }
        // The big model is out of budget for now. A smaller one still writes a usable
        // message, and a usable message beats the offline fallback's summary of nothing.
        if (smallModel != null && smallKeys != null) {
            Attempt small = attempt(request, smallModel, smallKeys);
            if (small.response() != null) {
                LOG.log(Level.INFO, "groq answered on {0} — {1} is rate limited", smallModel, model);
                return small.response();
            }
        }
        throw new LlmUnavailableException("all groq keys exhausted (" + big.error() + ")");
    }

    private record Attempt(LlmResponse response, String error) {
    }

    /** Anything shaped like a key, in case a provider ever echoes one back at us. */
    private static final java.util.regex.Pattern KEY_LIKE =
            java.util.regex.Pattern.compile("(?i)gsk_[A-Za-z0-9_-]+");
    private static final int MAX_HINT = 180;

    /**
     * The provider's own sentence about a refusal, appended to {@code lastError}.
     *
     * <p>"groq HTTP 429" says the calls stopped; it does not say whether to wait a minute or
     * until tomorrow. Groq answers a rate limit with the model, the limit that was hit and
     * how long it lasts — <em>"Rate limit reached for model llama-3.3-70b-versatile … Please
     * try again in 32m41s"</em> — and that is the one fact anyone looking at a degraded Relay
     * actually needs. Live, all five keys went to 429 within a second and the screen could
     * only say they were exhausted.
     *
     * <p>Shown on {@code /api/health/details}, which is behind the session. The body carries
     * no credential, but anything key-shaped is masked before it goes anywhere.
     */
    private static String hint(HttpTransport.Reply reply) {
        String message = "";
        try {
            message = Json.parse(reply.body()).path("error").path("message").asText("");
        } catch (RuntimeException e) {
            message = "";
        }
        if (message.isBlank() && reply.retryAfter() != null) {
            message = "retry-after " + reply.retryAfter().toSeconds() + "s";
        }
        if (message.isBlank()) {
            return "";
        }
        String cleaned = KEY_LIKE.matcher(message.replaceAll("\\s+", " ").trim()).replaceAll("gsk_***");
        if (cleaned.length() > MAX_HINT) {
            cleaned = cleaned.substring(0, MAX_HINT) + "…";
        }
        return " — " + cleaned;
    }

    /** One pass over the pool for a single model. */
    private Attempt attempt(LlmRequest request, String targetModel, ApiKeyPool pool) {
        String body = requestBody(request, targetModel);
        String lastError = "no key available";

        for (int tries = 0; tries < Math.max(1, pool.total()); tries++) {
            Optional<String> key = pool.next();
            if (key.isEmpty()) {
                break;
            }
            HttpTransport.Reply reply = transport.post(baseUrl + "/chat/completions", key.get(), body);
            if (reply.ok()) {
                return new Attempt(parse(reply.body(), targetModel), null);
            }
            lastError = "groq HTTP " + reply.status() + hint(reply);
            if (reply.shouldRotate()) {
                // A refused key (revoked, out of quota) never recovers; a rate limited one
                // does. Parking both for 60s would keep resurrecting a dead key.
                if (reply.refused()) {
                    pool.retire(key.get());
                    LOG.log(Level.WARNING, "groq key {0} retired ({1}) — provider refused it",
                            ApiKeyPool.mask(key.get()), reply.status());
                } else {
                    pool.penalize(key.get(), reply.retryAfter());
                    LOG.log(Level.WARNING, "groq key {0} parked on {1} ({2}, retry-after {3}) — rotating",
                            ApiKeyPool.mask(key.get()), targetModel, reply.status(),
                            String.valueOf(reply.retryAfter()));
                }
                continue;
            }
            // A genuine bad request (400 with a schema problem) will not be fixed by another key.
            throw new LlmUnavailableException("groq rejected the request: HTTP " + reply.status());
        }
        return new Attempt(null, lastError);
    }

    @Override
    public String name() {
        return "groq:" + model;
    }

    @Override
    public boolean degraded() {
        return keys.available() == 0 && (smallKeys == null || smallKeys.available() == 0);
    }

    public ApiKeyPool keys() {
        return keys;
    }

    public String model() {
        return model;
    }

    // -----------------------------------------------------------------------

    private String requestBody(LlmRequest request, String targetModel) {
        ObjectNode root = Json.object();
        root.put("model", targetModel);
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

    private LlmResponse parse(String body, String usedModel) {
        JsonNode root = Json.parse(body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        long promptTokens = root.path("usage").path("prompt_tokens").asLong(0);
        long completionTokens = root.path("usage").path("completion_tokens").asLong(0);
        double cost = (promptTokens / 1_000_000d) * inputUsdPerMillion
                + (completionTokens / 1_000_000d) * outputUsdPerMillion;
        return new LlmResponse(content, promptTokens, completionTokens, cost, usedModel, false);
    }
}
