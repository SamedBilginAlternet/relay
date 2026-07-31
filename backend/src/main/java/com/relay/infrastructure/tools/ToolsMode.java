package com.relay.infrastructure.tools;

import java.util.Locale;

/** {@code live} hits the real API, {@code replay} plays recorded fixtures. */
public enum ToolsMode {
    LIVE,
    REPLAY;

    public static ToolsMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return REPLAY;
        }
        return "live".equals(raw.trim().toLowerCase(Locale.ROOT)) ? LIVE : REPLAY;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
