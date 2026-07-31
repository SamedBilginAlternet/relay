package com.relay.application.port;

/** Completion plus the numbers the CostMeter needs. */
public record LlmResponse(String content, long promptTokens, long completionTokens, double costUsd,
                          String model, boolean fallback) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
