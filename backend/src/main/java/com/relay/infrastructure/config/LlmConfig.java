package com.relay.infrastructure.config;

import com.relay.application.port.Clock;
import com.relay.application.port.LlmClient;
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

    @Bean
    public GroqLlmClient groqLlmClient(ApiKeyPool pool, ApiKeyPool groqSmallKeyPool, HttpTransport transport,
                                       @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
                                       @Value("${app.groq.model:llama-3.3-70b-versatile}") String model,
                                       @Value("${app.groq.small-model:llama-3.1-8b-instant}") String smallModel,
                                       @Value("${app.groq.price.input-usd-per-million:0.59}") double input,
                                       @Value("${app.groq.price.output-usd-per-million:0.79}") double output) {
        return new GroqLlmClient(pool, transport, baseUrl, model, input, output, smallModel, groqSmallKeyPool);
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
    private static GroqLlmClient fallbackClient(HttpTransport transport, Clock clock, String rawKeys,
                                                String baseUrl, String model, String provider,
                                                double input, double output, long cooldownSeconds) {
        List<String> keys = parseKeys(rawKeys);
        if (keys.isEmpty()) {
            LOG.log(Level.INFO, "no fallback LLM configured — the stub is the only safety net");
            return null;
        }
        LOG.log(Level.INFO, "fallback LLM: {0} ({1}), {2} key(s)", provider, model, keys.size());
        ApiKeyPool pool = new ApiKeyPool(keys, Duration.ofSeconds(cooldownSeconds), clock);
        // No small-model tier: the point of this provider is that it has no daily wall to
        // duck under, so there is nothing to fall back to within it.
        return new GroqLlmClient(pool, transport, baseUrl, model, input, output, null, null, provider);
    }

    /** The bean the orchestrator gets: the free tier, a paid one behind it, then the stub. */
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
                               @Value("${app.groq.cooldown-seconds:60}") long cooldownSeconds) {
        return new RoutingLlmClient(groqLlmClient,
                fallbackClient(transport, clock, rawKeys, baseUrl, model, provider, input, output, cooldownSeconds),
                stub);
    }
}
