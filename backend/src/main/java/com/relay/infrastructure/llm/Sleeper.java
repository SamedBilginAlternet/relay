package com.relay.infrastructure.llm;

import java.time.Duration;

/** A wait as a dependency, so a test never actually blocks for it. */
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
