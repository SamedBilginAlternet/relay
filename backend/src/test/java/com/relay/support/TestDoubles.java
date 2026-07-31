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
