package com.relay.application.port;

/**
 * Completion plus the numbers the CostMeter needs.
 *
 * @param model          who answered, provider-qualified — {@code groq:llama-3.1-8b-instant}.
 *                       A bare model name stops identifying anything the moment a second
 *                       OpenAI-compatible provider serves a model under the same name.
 * @param premiumCostUsd what these same tokens would have cost on the strong model. Equal
 *                       to {@code costUsd} when the strong model is the one that answered.
 *                       {@code null} means "not derivable", which is not the same as zero:
 *                       the offline stub counts characters rather than tokens and no
 *                       provider ever billed them, so pricing them would invent a number.
 */
public record LlmResponse(String content, long promptTokens, long completionTokens, double costUsd,
                          String model, boolean fallback, Double premiumCostUsd) {

    /** For callers that cannot price their answer against the strong model. */
    public LlmResponse(String content, long promptTokens, long completionTokens, double costUsd,
                       String model, boolean fallback) {
        this(content, promptTokens, completionTokens, costUsd, model, fallback, null);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
