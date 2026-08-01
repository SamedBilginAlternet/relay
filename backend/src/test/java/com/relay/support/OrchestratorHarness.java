package com.relay.support;

import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.Summarizer;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import java.util.List;

/**
 * The one Coordinator every orchestrator test builds, built once.
 *
 * <p>The suite grew a test file per live incident — deliberately, and each file's Javadoc
 * carries its incident — but every one of them opened with the same ten lines: in-memory
 * doubles, a {@code CostMeter}, an {@code AgentJournal}, the {@code Coordinator} with its
 * seven collaborators, a {@code RunService} on a same-thread executor. Twenty-plus copies
 * of that paragraph meant twenty-plus edits per new constructor parameter, and the copies
 * had already started to drift (budget {@code 1.0} here, {@code null} there, {@code tools}
 * passed to the service in some files and not others).
 *
 * <p>This is a fixture, not a framework: it holds exactly the wiring the copies shared and
 * exposes every double as a public field, so a test that needs to reach into the repository
 * or the event log does so the same way it always did. Tests whose wiring is the point —
 * a custom executor, a restart across two coordinators, a recording policy engine — keep
 * building by hand.
 */
public final class OrchestratorHarness {

    public final TestDoubles.InMemoryRunRepository runs;
    public final TestDoubles.RecordingEventPublisher events;
    public final TestDoubles.InMemoryConnectionRepository connections;
    public final TestDoubles.InMemoryPolicyRepository policies;
    public final TestDoubles.FixedClock clock;
    public final CostMeter costMeter;
    public final AgentJournal journal;
    public final Coordinator coordinator;
    public final RunService service;

    private OrchestratorHarness(Builder b) {
        this.runs = b.runs;
        this.events = b.events;
        this.connections = b.connections;
        this.policies = b.policies;
        this.clock = b.clock;
        this.costMeter = new CostMeter();
        this.journal = new AgentJournal(events, clock);
        LlmClient llm = b.llm != null ? b.llm : new StubLlmClient(b.tools);
        this.coordinator = new Coordinator(runs,
                new Planner(llm, b.tools, costMeter, journal),
                new ToolAgent(b.tools, llm, connections, journal, clock),
                new Verifier(llm),
                new PolicyEngine(policies, b.tools),
                costMeter, events, journal, clock, b.summarizer);
        this.service = new RunService(runs, coordinator, journal, clock, Runnable::run,
                b.budgetUsd, b.tools);
    }

    /** The common case in one line: these tools, this model, everything else in memory. */
    public static OrchestratorHarness of(ToolRegistry tools, LlmClient llm) {
        return with(tools).llm(llm).build();
    }

    /** The stub model against these tools — the deterministic offline default. */
    public static OrchestratorHarness of(ToolRegistry tools) {
        return with(tools).build();
    }

    public static Builder with(ToolRegistry tools) {
        return new Builder(tools);
    }

    /** An empty registry, for tests that never reach a tool. */
    public static Builder withNoTools() {
        return new Builder(new ToolRegistryImpl(List.of()));
    }

    public static final class Builder {
        private final ToolRegistry tools;
        private LlmClient llm;
        private Summarizer summarizer;
        private Double budgetUsd = 1.0;
        private TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        private TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        private TestDoubles.InMemoryConnectionRepository connections =
                new TestDoubles.InMemoryConnectionRepository();
        private TestDoubles.InMemoryPolicyRepository policies = new TestDoubles.InMemoryPolicyRepository();
        private final TestDoubles.FixedClock clock = new TestDoubles.FixedClock();

        private Builder(ToolRegistry tools) {
            this.tools = tools;
        }

        public Builder llm(LlmClient llm) {
            this.llm = llm;
            return this;
        }

        public Builder summarizer(Summarizer summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        /** {@code null} means no default budget — the ApprovalScope shape. */
        public Builder budgetUsd(Double budgetUsd) {
            this.budgetUsd = budgetUsd;
            return this;
        }

        /** A repository the test also holds — shared state across two harnesses, or a spy. */
        public Builder runs(TestDoubles.InMemoryRunRepository runs) {
            this.runs = runs;
            return this;
        }

        public Builder events(TestDoubles.RecordingEventPublisher events) {
            this.events = events;
            return this;
        }

        public Builder connections(TestDoubles.InMemoryConnectionRepository connections) {
            this.connections = connections;
            return this;
        }

        public Builder policies(TestDoubles.InMemoryPolicyRepository policies) {
            this.policies = policies;
            return this;
        }

        public OrchestratorHarness build() {
            return new OrchestratorHarness(this);
        }
    }
}
