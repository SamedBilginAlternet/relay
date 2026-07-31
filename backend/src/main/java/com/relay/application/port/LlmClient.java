package com.relay.application.port;

/**
 * The only thing the orchestrator knows about language models.
 * Implementations: GroqLlmClient (multi key, rotating) and StubLlmClient (offline, deterministic).
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /** Human readable id, e.g. {@code groq:llama-3.3-70b-versatile} or {@code stub}. */
    String name();

    /** True when we are not running on the primary provider (all keys burned, stub fallback). */
    boolean degraded();
}
