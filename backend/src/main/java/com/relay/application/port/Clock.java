package com.relay.application.port;

import java.time.Instant;

/** Time as a dependency, so tests do not sleep. */
public interface Clock {

    Instant now();

    static Clock system() {
        return Instant::now;
    }
}
