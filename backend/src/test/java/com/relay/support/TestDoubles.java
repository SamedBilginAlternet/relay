package com.relay.support;

import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.EventPublisher;
import com.relay.application.port.PolicyRepository;
import com.relay.application.port.RunEvent;
import com.relay.application.port.RunRepository;
import com.relay.domain.Connection;
import com.relay.domain.Run;
import com.relay.domain.ToolPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory ports so the orchestrator can be tested without Spring, JPA or a network. */
public final class TestDoubles {

    private TestDoubles() {
    }

    /** Time under our control. */
    public static class FixedClock implements Clock {
        private Instant now = Instant.parse("2026-07-31T09:00:00Z");

        @Override
        public Instant now() {
            return now;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        public void set(Instant instant) {
            now = instant;
        }
    }

    /** Keeps the live aggregate — enough for the orchestrator, which mutates in place. */
    public static class InMemoryRunRepository implements RunRepository {
        private final Map<UUID, Run> runs = new LinkedHashMap<>();

        @Override
        public Run save(Run run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<Run> findById(UUID id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public List<Run> findAll(int page, int size) {
            List<Run> all = new ArrayList<>(runs.values());
            all.sort(Comparator.comparing(Run::createdAt).reversed());
            int from = Math.min(page * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count() {
            return runs.size();
        }
    }

    public static class InMemoryPolicyRepository implements PolicyRepository {
        private final Map<String, ToolPolicy> policies = new LinkedHashMap<>();

        @Override
        public List<ToolPolicy> findAll() {
            return new ArrayList<>(policies.values());
        }

        @Override
        public Optional<ToolPolicy> findByToolName(String toolName) {
            return Optional.ofNullable(policies.get(toolName));
        }

        @Override
        public ToolPolicy save(ToolPolicy policy) {
            policies.put(policy.toolName(), policy);
            return policy;
        }
    }

    public static class InMemoryConnectionRepository implements ConnectionRepository {
        private final Map<String, Connection> connections = new LinkedHashMap<>();

        @Override
        public List<Connection> findAll() {
            return new ArrayList<>(connections.values());
        }

        @Override
        public Optional<Connection> findByProvider(String provider) {
            return Optional.ofNullable(connections.get(provider));
        }

        @Override
        public Connection save(Connection connection) {
            connections.put(connection.provider(), connection);
            return connection;
        }
    }

    /** An LLM that always answers with the same canned content. */
    public static class StaticLlmClient implements com.relay.application.port.LlmClient {
        private final String content;
        public final List<com.relay.application.port.LlmRequest> requests = new ArrayList<>();

        public StaticLlmClient(String content) {
            this.content = content;
        }

        @Override
        public com.relay.application.port.LlmResponse complete(
                com.relay.application.port.LlmRequest request) {
            requests.add(request);
            return new com.relay.application.port.LlmResponse(content, 100, 50, 0.0002, "static", false);
        }

        @Override
        public String name() {
            return "static";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    /**
     * An LLM with a different canned answer per {@code LlmPurpose} — needed once a single
     * request (the brief) makes more than one call and each expects its own shape.
     */
    public static class ScriptedLlmClient implements com.relay.application.port.LlmClient {
        private final Map<String, String> byPurpose;
        private final boolean degraded;
        public final List<com.relay.application.port.LlmRequest> requests = new ArrayList<>();

        public ScriptedLlmClient(Map<String, String> byPurpose) {
            this(byPurpose, false);
        }

        public ScriptedLlmClient(Map<String, String> byPurpose, boolean degraded) {
            this.byPurpose = byPurpose;
            this.degraded = degraded;
        }

        @Override
        public com.relay.application.port.LlmResponse complete(
                com.relay.application.port.LlmRequest request) {
            requests.add(request);
            String content = byPurpose.getOrDefault(request.purpose(), "");
            return new com.relay.application.port.LlmResponse(content, 100, 50, 0.0002, "scripted", false);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean degraded() {
            return degraded;
        }

        public List<com.relay.application.port.LlmRequest> of(String purpose) {
            return requests.stream().filter(r -> purpose.equals(r.purpose())).toList();
        }
    }

    /** A tool that always fails — the "provider is down" case. */
    public static class FailingTool implements com.relay.application.port.Tool {
        private final String name;

        public FailingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "always fails";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode schema() {
            var schema = com.relay.application.json.Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public com.relay.domain.RiskLevel risk() {
            return com.relay.domain.RiskLevel.READ;
        }

        @Override
        public com.relay.application.port.ToolResult execute(
                com.fasterxml.jackson.databind.JsonNode params, Connection connection) {
            return com.relay.application.port.ToolResult.error("provider exploded", 3, "live");
        }
    }

    /**
     * A tool that exists only in the registry — a name, a provider and a parameter list.
     *
     * <p>Written for the suggestion layer, which has to behave correctly around tools that
     * are not built yet: it looks a draft tool up by what it does and seeds its parameters
     * from whatever schema it declares. That behaviour cannot be tested against a tool this
     * repository already ships, because then the name could just as well be hard-coded.
     */
    public static class NamedTool implements com.relay.application.port.Tool {
        private final String name;
        private final String provider;
        private final List<String> required;
        private final List<String> optional;

        public NamedTool(String name, String provider, List<String> required, List<String> optional) {
            this.name = name;
            this.provider = provider;
            this.required = required;
            this.optional = optional;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public String description() {
            return "test double for " + name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode schema() {
            var schema = com.relay.application.json.Json.object();
            schema.put("type", "object");
            var req = schema.putArray("required");
            required.forEach(req::add);
            var props = schema.putObject("properties");
            required.forEach(field -> props.putObject(field).put("type", "string"));
            optional.forEach(field -> props.putObject(field).put("type", "string"));
            return schema;
        }

        @Override
        public com.relay.domain.RiskLevel risk() {
            return com.relay.domain.RiskLevel.WRITE;
        }

        @Override
        public com.relay.application.port.ToolResult execute(
                com.fasterxml.jackson.databind.JsonNode params, Connection connection) {
            return com.relay.application.port.ToolResult.ok(params, 1, "replay");
        }
    }

    /** Records every SSE frame so assertions can read the timeline. */
    public static class RecordingEventPublisher implements EventPublisher {
        public final List<RunEvent> events = new ArrayList<>();

        @Override
        public void publish(UUID runId, RunEvent event) {
            events.add(event);
        }

        public List<RunEvent> ofType(String type) {
            return events.stream().filter(e -> e.type().equals(type)).toList();
        }

        public boolean has(String type) {
            return events.stream().anyMatch(e -> e.type().equals(type));
        }
    }
}
