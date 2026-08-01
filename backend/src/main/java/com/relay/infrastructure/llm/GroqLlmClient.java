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
    /**
     * Whose API this is, in the sentences an operator reads.
     *
     * <p>The class speaks OpenAI's chat-completions dialect, which is also DeepSeek's,
     * Cerebras', Together's and everyone else's. Once a second provider is configured, an
     * error line that says "groq HTTP 429" about a DeepSeek refusal sends whoever reads it
     * to the wrong console.
     */
    private final String provider;

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion) {
        this(keys, transport, baseUrl, model, inputUsdPerMillion, outputUsdPerMillion, null, null);
    }

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion,
                         String smallModel, ApiKeyPool smallKeys) {
        this(keys, transport, baseUrl, model, inputUsdPerMillion, outputUsdPerMillion,
                smallModel, smallKeys, "groq");
    }

    public GroqLlmClient(ApiKeyPool keys, HttpTransport transport, String baseUrl, String model,
                         double inputUsdPerMillion, double outputUsdPerMillion,
                         String smallModel, ApiKeyPool smallKeys, String provider) {
        this.provider = provider == null || provider.isBlank() ? "groq" : provider.trim();
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
        String smallError = null;
        if (smallModel != null && smallKeys != null) {
            Attempt small = attempt(request, smallModel, smallKeys);
            if (small.response() != null) {
                LOG.log(Level.INFO, provider + " answered on {0} — {1} is rate limited", smallModel, model);
                return small.response();
            }
            smallError = small.error();
        }
        // Both tiers, both named. The two models have separate limits, so "everything is
        // exhausted" and "the big model is exhausted and the small one was never tried"
        // are different situations and used to read the same on the health endpoint.
        throw new LlmUnavailableException("all " + provider + " keys exhausted (" + model + ": " + big.error()
                + (smallError == null ? "" : "; " + smallModel + ": " + smallError) + ")");
    }

    private record Attempt(LlmResponse response, String error) {
    }

    /** Anything shaped like a key, in case a provider ever echoes one back at us. */
    private static final java.util.regex.Pattern KEY_LIKE =
            java.util.regex.Pattern.compile("(?i)gsk_[A-Za-z0-9_-]+");
    /**
     * Long enough to keep the tail of Groq's sentence. At 180 the line stopped one clause
     * short — "…tokens per day (TPD): Limit 100000, Used 99134, …" — and the clause it cut
     * was "Please try again in 32m41s", which is the only part anyone reads it for.
     */
    private static final int MAX_HINT = 300;

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

    /**
     * The last refusal each model answered with, kept so a pool that is merely cooling down
     * can still say why.
     *
     * <p>Live, the small tier reported {@code "no key available"} and nothing else — which
     * looks identical whether the small model is out of budget or was never configured. The
     * one is a spent quota, the other a missing {@code GROQ_SMALL_MODEL}, and they call for
     * opposite actions.
     */
    private final java.util.Map<String, String> lastRefusal = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every Groq organisation that has refused one of our keys.
     *
     * <p>The limit is counted per organisation, so the only question that matters when the
     * keys run out is how many organisations they belong to — five keys against one budget
     * behave exactly like one key, and no amount of rotation changes that. Nothing in the
     * product could answer it: a refusal names the organisation, but only the last one
     * survived. The ids are collected instead, and shown to a signed-in operator.
     *
     * <p>An organisation id is not a credential and cannot be used to call anything.
     */
    private final java.util.Set<String> organisations =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.regex.Pattern ORGANISATION =
            java.util.regex.Pattern.compile("organization `?(org_[A-Za-z0-9]+)`?");

    /** Distinct Groq organisations seen refusing a key, in a stable order. */
    public java.util.List<String> organisations() {
        return organisations.stream().sorted().toList();
    }

    /** One pass over the pool for a single model. */
    private Attempt attempt(LlmRequest request, String targetModel, ApiKeyPool pool) {
        String body = requestBody(request, targetModel);
        String remembered = lastRefusal.get(targetModel);
        String lastError = remembered == null
                ? "no key available"
                : "no key available, still cooling from: " + remembered;

        for (int tries = 0; tries < Math.max(1, pool.total()); tries++) {
            Optional<String> key = pool.next();
            if (key.isEmpty()) {
                break;
            }
            HttpTransport.Reply reply = transport.post(baseUrl + "/chat/completions", key.get(), body);
            if (reply.ok()) {
                return new Attempt(parse(reply.body(), targetModel), null);
            }
            lastError = provider + " HTTP " + reply.status() + hint(reply);
            lastRefusal.put(targetModel, lastError);
            java.util.regex.Matcher org = ORGANISATION.matcher(lastError);
            if (org.find()) {
                organisations.add(org.group(1));
            }
            if (reply.shouldRotate()) {
                // A refused key (revoked, out of quota) never recovers; a rate limited one
                // does. Parking both for 60s would keep resurrecting a dead key.
                if (reply.refused()) {
                    pool.retire(key.get());
                    LOG.log(Level.WARNING, provider + " key {0} retired ({1}) — provider refused it",
                            ApiKeyPool.mask(key.get()), reply.status());
                } else {
                    // A balance that ran out is fixed by a top-up, not by a retry a minute
                    // later, and the provider sends no Retry-After for it. Park it long
                    // enough that a payment is noticed without a deploy.
                    java.time.Duration wait = reply.outOfCredit() && reply.retryAfter() == null
                            ? ApiKeyPool.MAX_PARK
                            : reply.retryAfter();
                    pool.penalize(key.get(), wait);
                    LOG.log(Level.WARNING, provider + " key {0} parked on {1} ({2}, retry-after {3}) — rotating",
                            ApiKeyPool.mask(key.get()), targetModel, reply.status(),
                            String.valueOf(wait));
                }
                continue;
            }
            // A genuine bad request (400 with a schema problem) will not be fixed by another key.
            throw new LlmUnavailableException(provider + " rejected the request: HTTP " + reply.status());
        }
        return new Attempt(null, lastError);
    }

    @Override
    public String name() {
        return provider + ":" + model;
    }

    /** {@code groq}, {@code deepseek} — whose console to open when this one is refusing. */
    public String provider() {
        return provider;
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
