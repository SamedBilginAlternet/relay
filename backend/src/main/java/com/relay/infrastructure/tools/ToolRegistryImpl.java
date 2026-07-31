package com.relay.infrastructure.tools;

import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link Tool} bean on the classpath, indexed by name. Adding an integration is
 * adding a class — nothing here changes.
 */
@Component
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistryImpl(List<Tool> tools) {
        tools.stream()
                .sorted(Comparator.comparing(Tool::name))
                .forEach(tool -> byName.put(tool.name(), tool));
    }

    @Override
    public List<Tool> all() {
        return List.copyOf(byName.values());
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
