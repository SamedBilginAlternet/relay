package com.relay.domain;

/** Governance override for one tool. Absence means "use the risk default". */
public record ToolPolicy(String provider, String toolName, PolicyMode mode) {

    public ToolPolicy {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    public static String providerOf(String toolName) {
        int dot = toolName.indexOf('.');
        return dot > 0 ? toolName.substring(0, dot) : toolName;
    }
}
