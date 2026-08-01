package com.relay.infrastructure.config;

import com.relay.application.port.Clock;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.ApiKeyPool;
import com.relay.infrastructure.llm.GroqLlmClient;
import com.relay.infrastructure.llm.HttpTransport;
import com.relay.infrastructure.llm.JdkHttpTransport;
import com.relay.infrastructure.llm.RoutingLlmClient;
import com.relay.infrastructure.llm.StubLlmClient;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Primary;

/**
 * LLM wiring. With no {@code GROQ_API_KEYS} the app still boots and runs — on the stub.
 */
@Configuration
public class LlmConfig {

    private static final Logger LOG = System.getLogger(LlmConfig.class.getName());

    @Bean
    public HttpTransport llmTransport() {
        return new JdkHttpTransport(Duration.ofSeconds(30));
    }

    @Bean
    @Primary
    public ApiKeyPool groqKeyPool(@Value("${app.groq.api-keys:}") String rawKeys,
                                  @Value("${app.groq.cooldown-seconds:60}") long cooldownSeconds,
                                  Clock clock) {
        List<String> keys = parseKeys(rawKeys);
        LOG.log(Level.INFO, "groq keys configured: {0}", keys.size());
        return new ApiKeyPool(keys, Duration.ofSeconds(cooldownSeconds), clock);
    }

    /**
     * The same keys, tracked separately for the small model.
     *
     * <p>Groq rate limits per model, so a key parked on the big model can still answer on
     * the small one. One shared pool would hide that capacity exactly when it is needed.
     */
    @Bean
    public ApiKeyPool groqSmallKeyPool(@Value("${app.groq.api-keys:}") String rawKeys,
                                       @Value("${app.groq.cooldown-seconds:60}") long cooldownSeconds,
                                       Clock clock) {
        return new ApiKeyPool(parseKeys(rawKeys), Duration.ofSeconds(cooldownSeconds), clock);
    }

    private static List<String> parseKeys(String rawKeys) {
        return Arrays.stream((rawKeys == null ? "" : rawKeys).split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
    }

    @Bean
    public StubLlmClient stubLlmClient(ToolRegistry tools) {
        return new StubLlmClient(tools);
    }

    /**
     * The two tiers and the map from job to tier.
     *
     * <p>{@code app.llm.small-purposes} is read as a property rather than compiled in so the
     * split can be moved — a purpose promoted to the strong model, or demoted — without a
     * deploy. The two price lists are separate for the same reason and for one more: billing
     * both tiers at the strong model's rate, which is what this code did, hides the saving
     * the split exists to produce.
     */
    @Bean
    public GroqLlmClient groqLlmClient(ApiKeyPool pool, ApiKeyPool groqSmallKeyPool, HttpTransport transport,
                                       @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
                                       @Value("${app.groq.model:llama-3.3-70b-versatile}") String model,
                                       @Value("${app.groq.small-model:llama-3.1-8b-instant}") String smallModel,
                                       @Value("${app.groq.price.input-usd-per-million:0.59}") double input,
                                       @Value("${app.groq.price.output-usd-per-million:0.79}") double output,
                                       @Value("${app.groq.small-price.input-usd-per-million:0.05}") double smallInput,
                                       @Value("${app.groq.small-price.output-usd-per-million:0.08}") double smallOutput,
                                       @Value("${app.groq.provider:groq}") String provider,
                                       @Value("${app.llm.small-purposes:}") String smallPurposes) {
        Collection<String> purposes = parsePurposes(smallPurposes);
        LOG.log(Level.INFO, "primary LLM: {0} ({1}); small model {2} handles: {3}",
                provider, model, smallModel, purposes);
        /*
          The label is configurable because this client is not Groq-specific — it speaks
          OpenAI's chat-completions shape and the fallback tier already points the same
          class at DeepSeek. Pointing the PRIMARY tier elsewhere used to leave the name
          behind: every step would have read `groq:deepseek-v4-flash` on screen, which is
          a provider that does not exist. The name of who answered is the one thing the
          cost columns rest on.
        */
        return new GroqLlmClient(pool, transport, baseUrl, model, input, output, smallModel, groqSmallKeyPool,
                provider, smallInput, smallOutput, purposes);
    }

    /**
     * Blank means "use the default map", not "route nothing to the small model" — an unset
     * property is the common case and it should behave like the shipped decision. To turn the
     * split off, name a purpose that does not exist, or set every purpose on the strong side.
     */
    private static Collection<String> parsePurposes(String raw) {
        if (raw == null || raw.isBlank()) {
            return LlmPurpose.DEFAULT_SMALL;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(purpose -> !purpose.isEmpty())
                .toList();
    }

    /**
     * The paid tier behind the free one, or {@code null} when no keys were given.
     *
     * <p>Built inside the bean that uses it rather than as a bean of its own: a {@code @Bean}
     * method that returns null hands Spring a null bean, and injecting one of those is a
     * detail nobody should have to remember on a deploy that has no context test to catch it.
     *
     * <p>Any OpenAI-compatible endpoint fits — the request this code sends is
     * {@code POST {base}/chat/completions} with a bearer key, and the answer it reads is
     * {@code choices[0].message.content} plus {@code usage}. DeepSeek, Cerebras, Together and
     * OpenRouter all speak it, so choosing one is three environment variables rather than a
     * new client.
     */
    private static GroqLlmClient behindClient(HttpTransport transport, Clock clock, String rawKeys,
                                              String baseUrl, String model, String provider,
                                              double input, double output, long cooldownSeconds) {
        List<String> keys = parseKeys(rawKeys);
        // A tier with no keys, no endpoint or no model is a tier nobody configured. Building
        // it anyway would put a client in the chain that fails every call it is handed and
        // reports the failure as an outage.
        if (keys.isEmpty() || baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return null;
        }
        LOG.log(Level.INFO, "behind the primary: {0} ({1}), {2} key(s)", provider, model, keys.size());
        ApiKeyPool pool = new ApiKeyPool(keys, Duration.ofSeconds(cooldownSeconds), clock);
        // No small-model tier: the point of this provider is that it has no daily wall to
        // duck under, so there is nothing to fall back to within it.
        return new GroqLlmClient(pool, transport, baseUrl, model, input, output, null, null, provider);
    }

    /**
     * The bean the orchestrator gets: the tiers in preference order, then the stub.
     *
     * <p>Three, not two, and the third is not decoration. On 2026-08-01 all seven Groq keys
     * hit their daily wall and the paid provider answered {@code HTTP 599} within the same
     * hour — both configured tiers down at once, leaving the stub, which writes no digest
     * and no summary. A third provider is a bet on three companies not having a bad hour
     * together, which is a materially different bet from two.
     *
     * <p>Every tier is the same client class pointed somewhere else: this code posts
     * {@code {base}/chat/completions} with a bearer key and reads
     * {@code choices[0].message.content} plus {@code usage}. Groq, DeepSeek, Gemini's
     * OpenAI-compatible endpoint, Cerebras, Together and OpenRouter all speak it, so adding
     * a provider is environment variables rather than code.
     */
    @Bean
    @Primary
    public LlmClient llmClient(GroqLlmClient groqLlmClient, StubLlmClient stub,
                               HttpTransport transport, Clock clock,
                               @Value("${app.llm.fallback.api-keys:}") String rawKeys,
                               @Value("${app.llm.fallback.base-url:https://api.deepseek.com}") String baseUrl,
                               @Value("${app.llm.fallback.model:deepseek-v4-flash}") String model,
                               @Value("${app.llm.fallback.provider:deepseek}") String provider,
                               @Value("${app.llm.fallback.price.input-usd-per-million:0.14}") double input,
                               @Value("${app.llm.fallback.price.output-usd-per-million:0.28}") double output,
                               @Value("${app.llm.third.api-keys:}") String thirdKeys,
                               @Value("${app.llm.third.base-url:}") String thirdBaseUrl,
                               @Value("${app.llm.third.model:}") String thirdModel,
                               @Value("${app.llm.third.provider:third}") String thirdProvider,
                               @Value("${app.llm.third.price.input-usd-per-million:0}") double thirdInput,
                               @Value("${app.llm.third.price.output-usd-per-million:0}") double thirdOutput,
                               @Value("${app.groq.cooldown-seconds:60}") long cooldownSeconds) {
        // `Arrays.asList`, not `List.of`: an unconfigured tier is null and the router is
        // what drops it. `List.of` throws on a null element, which would turn "no third
        // provider" into a failure to start.
        return new RoutingLlmClient(java.util.Arrays.asList(
                groqLlmClient,
                behindClient(transport, clock, rawKeys, baseUrl, model, provider, input, output,
                        cooldownSeconds),
                behindClient(transport, clock, thirdKeys, thirdBaseUrl, thirdModel, thirdProvider,
                        thirdInput, thirdOutput, cooldownSeconds)),
                stub);
    }
}
