package com.relay.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.ToolRegistry;
import com.relay.domain.PolicyMode;
import com.relay.domain.ToolPolicy;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private TestDoubles.InMemoryPolicyRepository policies;
    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry registry = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
        policies = new TestDoubles.InMemoryPolicyRepository();
        engine = new PolicyEngine(policies, registry);
    }

    @Test
    void readToolsRunAutomatically() {
        PolicyDecision decision = engine.evaluate("jira.searchIssues");
        assertThat(decision.mode()).isEqualTo(PolicyMode.AUTO);
        assertThat(decision.auto()).isTrue();
        assertThat(decision.explicit()).isFalse();
    }

    @Test
    void writeToolsAskForApproval() {
        assertThat(engine.evaluate("jira.updateIssue").mode()).isEqualTo(PolicyMode.ASK);
        assertThat(engine.evaluate("slack.postMessage").ask()).isTrue();
    }

    @Test
    void unknownToolIsForbidden() {
        PolicyDecision decision = engine.evaluate("stripe.refundEverything");
        assertThat(decision.forbidden()).isTrue();
        assertThat(decision.reason()).contains("unknown tool");
    }

    @Test
    void stepWithoutAToolIsAlwaysAuto() {
        assertThat(engine.evaluate(null).auto()).isTrue();
        assertThat(engine.evaluate("  ").auto()).isTrue();
    }

    @Test
    void operatorOverrideBeatsTheRiskDefault() {
        policies.save(new ToolPolicy("slack", "slack.postMessage", PolicyMode.FORBIDDEN));
        PolicyDecision decision = engine.evaluate("slack.postMessage");
        assertThat(decision.forbidden()).isTrue();
        assertThat(decision.explicit()).isTrue();

        policies.save(new ToolPolicy("jira", "jira.updateIssue", PolicyMode.AUTO));
        assertThat(engine.evaluate("jira.updateIssue").auto()).isTrue();
    }

    @Test
    void effectivePoliciesCoverEveryRegisteredTool() {
        policies.save(new ToolPolicy("jira", "jira.updateIssue", PolicyMode.AUTO));
        List<PolicyEngine.EffectivePolicy> effective = engine.effectivePolicies();

        assertThat(effective).hasSize(3);
        assertThat(effective).anySatisfy(policy -> {
            assertThat(policy.toolName()).isEqualTo("jira.updateIssue");
            assertThat(policy.mode()).isEqualTo(PolicyMode.AUTO);
            assertThat(policy.overridden()).isTrue();
        });
        assertThat(effective).anySatisfy(policy -> {
            assertThat(policy.toolName()).isEqualTo("slack.postMessage");
            assertThat(policy.mode()).isEqualTo(PolicyMode.ASK);
            assertThat(policy.overridden()).isFalse();
        });
    }
}
