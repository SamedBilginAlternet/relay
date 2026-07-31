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
    public ApiKeyPool groqKeyPool(@Value("${app.groq.api-keys:}") String rawKeys,
                                  @Value("${app.groq.cooldown-seconds:60}") long cooldownSeconds,
                                  Clock clock) {
        List<String> keys = Arrays.stream((rawKeys == null ? "" : rawKeys).split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
        LOG.log(Level.INFO, "groq keys configured: {0}", keys.size());
        return new ApiKeyPool(keys, Duration.ofSeconds(cooldownSeconds), clock);
    }

    @Bean
    public StubLlmClient stubLlmClient(ToolRegistry tools) {
        return new StubLlmClient(tools);
    }

    @Bean
    public GroqLlmClient groqLlmClient(ApiKeyPool pool, HttpTransport transport,
                                       @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
                                       @Value("${app.groq.model:llama-3.3-70b-versatile}") String model,
                                       @Value("${app.groq.price.input-usd-per-million:0.59}") double input,
                                       @Value("${app.groq.price.output-usd-per-million:0.79}") double output) {
        return new GroqLlmClient(pool, transport, baseUrl, model, input, output);
    }

    /** The bean the orchestrator gets: Groq with a stub safety net. */
    @Bean
    @Primary
    public LlmClient llmClient(GroqLlmClient groq, StubLlmClient stub) {
        return new RoutingLlmClient(groq, stub);
    }
}
