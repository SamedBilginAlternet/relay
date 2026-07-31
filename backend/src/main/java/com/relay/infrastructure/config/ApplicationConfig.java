package com.relay.infrastructure.config;

import com.relay.application.assistant.AskService;
import com.relay.application.assistant.SourceRouter;
import com.relay.application.brief.BriefService;
import com.relay.application.brief.DigestService;
import com.relay.application.brief.InsightService;
import com.relay.application.connection.ConnectionService;
import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.playbook.PlaybookService;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Summarizer;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.EventPublisher;
import com.relay.application.port.LlmClient;
import com.relay.application.port.PolicyRepository;
import com.relay.application.port.RunRepository;
import com.relay.application.port.ToolRegistry;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application layer. The orchestrator classes carry no Spring annotations —
 * dependency direction stays: api → application → domain, with infrastructure plugged in here.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    public Clock clock() {
        return Clock.system();
    }

    @Bean
    public CostMeter costMeter() {
        return new CostMeter();
    }

    @Bean
    public PolicyEngine policyEngine(PolicyRepository policies, ToolRegistry tools) {
        return new PolicyEngine(policies, tools);
    }

    @Bean
    public AgentJournal agentJournal(EventPublisher events, Clock clock) {
        return new AgentJournal(events, clock);
    }

    @Bean
    public Planner planner(LlmClient llm, ToolRegistry tools, CostMeter costMeter, AgentJournal journal) {
        return new Planner(llm, tools, costMeter, journal);
    }

    @Bean
    public ToolAgent toolAgent(ToolRegistry tools, LlmClient llm, ConnectionRepository connections,
                               AgentJournal journal, Clock clock) {
        return new ToolAgent(tools, llm, connections, journal, clock);
    }

    @Bean
    public Verifier verifier(LlmClient llm) {
        return new Verifier(llm);
    }

    @Bean
    public Summarizer summarizer(LlmClient llm) {
        return new Summarizer(llm);
    }

    @Bean
    public Coordinator coordinator(RunRepository runs, Planner planner, ToolAgent toolAgent, Verifier verifier,
                                   PolicyEngine policyEngine, CostMeter costMeter, EventPublisher events,
                                   AgentJournal journal, Clock clock, Summarizer summarizer) {
        return new Coordinator(runs, planner, toolAgent, verifier, policyEngine, costMeter, events, journal,
                clock, summarizer);
    }

    /** Runs are driven off the request thread — POST /api/runs returns immediately. */
    @Bean(destroyMethod = "shutdown")
    public Executor orchestratorExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "relay-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public RunService runService(RunRepository runs, Coordinator coordinator, AgentJournal journal, Clock clock,
                                 Executor orchestratorExecutor, ToolRegistry tools,
                                 @Value("${app.budget.default-usd:0.50}") Double defaultBudgetUsd) {
        return new RunService(runs, coordinator, journal, clock, orchestratorExecutor, defaultBudgetUsd, tools);
    }

    /**
     * The brief fans every READ tool out at once; virtual threads make a per-tool
     * timeout cheap enough to give each provider its own.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService briefExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public InsightService insightService(LlmClient llm, ToolRegistry tools) {
        return new InsightService(llm, tools);
    }

    @Bean
    public DigestService digestService(LlmClient llm) {
        return new DigestService(llm);
    }

    @Bean
    public BriefService briefService(ToolRegistry tools, ConnectionRepository connections,
                                     InsightService insights, DigestService digests,
                                     LlmClient llm, Clock clock,
                                     ExecutorService briefExecutor,
                                     @Value("${app.brief.tool-timeout-seconds:8}") long timeoutSeconds,
                                     @Value("${app.brief.cache-seconds:180}") long cacheSeconds,
                                     @Value("${app.brief.timezone:Europe/Istanbul}") String timezone,
                                     @Value("${app.brief.default-project-key:RELAY}") String projectKey) {
        return new BriefService(tools, connections, insights, digests, llm, clock, briefExecutor,
                Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(cacheSeconds), timezone, projectKey);
    }

    @Bean
    public SourceRouter sourceRouter(LlmClient llm, ToolRegistry tools) {
        return new SourceRouter(llm, tools);
    }

    @Bean
    public AskService askService(ToolRegistry tools, ConnectionRepository connections,
                                 SourceRouter router, LlmClient llm) {
        return new AskService(tools, connections, router, llm);
    }

    @Bean
    public PlaybookService playbookService(ToolRegistry tools, ConnectionRepository connections,
                                           RunService runService) {
        return new PlaybookService(tools, connections, runService);
    }

    @Bean
    public ConnectionService connectionService(ConnectionRepository connections, ToolRegistry tools, Clock clock) {
        return new ConnectionService(connections, tools, clock);
    }
}
