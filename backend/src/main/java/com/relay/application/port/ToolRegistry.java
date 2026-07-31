package com.relay.application.port;

import java.util.List;
import java.util.Optional;

/** Everything the system can do. Populated from the classpath. */
public interface ToolRegistry {

    List<Tool> all();

    Optional<Tool> find(String name);
}
