package com.relay.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * Governance is what Relay is asked to demonstrate, and one of its three defaults was never
 * run: no registered tool declares {@code DESTRUCTIVE}, so "silme yasak" existed as a switch
 * branch and a sentence in the docs and nowhere else. {@code forbidden} was only ever proven
 * through an <em>unknown</em> tool name, which is a different rule entirely.
 *
 * <p>So a destructive tool is registered here on purpose. It also carries the decision that
 * came with it: an operator may make a destructive tool stricter, never fully automatic —
 * between a mistyped policy and an irreversible delete there is always a person.
 */
class PolicyEngineTest {

    private TestDoubles.InMemoryPolicyRepository policies;
    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry registry = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures),
                new TestDoubles.DestructiveTool()));
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
    void a_destructive_tool_is_forbidden_without_anyone_configuring_it() {
        PolicyDecision decision = engine.evaluate("jira.deleteIssue");

        assertThat(decision.forbidden()).isTrue();
        // Nobody wrote a policy row: the refusal comes from the tool's own risk level.
        assertThat(decision.explicit()).isFalse();
        // The reason is read on the approval card, so it names the risk in Turkish (#81).
        assertThat(decision.reason()).isEqualTo("yıkıcı riski varsayılanı: yasak");
        assertThat(decision.auto()).isFalse();
        assertThat(decision.ask()).isFalse();
    }

    @Test
    void unknownToolIsForbidden() {
        PolicyDecision decision = engine.evaluate("stripe.refundEverything");
        assertThat(decision.forbidden()).isTrue();
        assertThat(decision.reason()).contains("tanımsız araç");
    }

    /**
     * The reason is the grounds the approval card gives for stopping — the single
     * most-read sentence in the product, and it read "default for write risk: ask" in an
     * interface that is Turkish everywhere else (#81).
     */
    @Test
    void the_grounds_for_asking_are_written_in_the_language_of_the_screen() {
        assertThat(engine.evaluate("slack.postMessage").reason())
                .isEqualTo("yazma riski varsayılanı: onay ister");
        assertThat(engine.evaluate("jira.searchIssues").reason())
                .isEqualTo("okuma riski varsayılanı: otomatik");

        policies.save(new ToolPolicy("jira", "jira.updateIssue", PolicyMode.FORBIDDEN));
        assertThat(engine.evaluate("jira.updateIssue").reason())
                .isEqualTo("jira.updateIssue için kayıtlı politika: yasak");

        assertThat(engine.evaluate(null).reason()).doesNotContain("reasoning step");
        for (String english : List.of("write risk", "policy override", "default for", "unknown tool")) {
            assertThat(engine.evaluate("slack.postMessage").reason()).doesNotContain(english);
            assertThat(engine.evaluate("stripe.refundEverything").reason()).doesNotContain(english);
        }
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
    void an_operator_cannot_turn_a_destructive_tool_fully_automatic() {
        assertThatThrownBy(() -> engine.set("jira.deleteIssue", PolicyMode.AUTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto yapılamaz");

        // Nothing was written: the refusal is not a half-applied policy.
        assertThat(policies.findByToolName("jira.deleteIssue")).isEmpty();
        assertThat(engine.evaluate("jira.deleteIssue").forbidden()).isTrue();
    }

    /** Somebody has to be able to run the delete at all — with a person in front of it. */
    @Test
    void an_operator_may_soften_a_destructive_tool_as_far_as_ask() {
        engine.set("jira.deleteIssue", PolicyMode.ASK);

        PolicyDecision decision = engine.evaluate("jira.deleteIssue");
        assertThat(decision.ask()).isTrue();
        assertThat(decision.explicit()).isTrue();
    }

    /**
     * The rule cannot depend on the API having been used: policy rows live in a table, and one
     * can arrive from an older build or from a hand-written UPDATE.
     */
    @Test
    void a_destructive_auto_row_written_behind_the_engines_back_still_stops_for_a_human() {
        policies.save(new ToolPolicy("jira", "jira.deleteIssue", PolicyMode.AUTO));

        PolicyDecision decision = engine.evaluate("jira.deleteIssue");
        assertThat(decision.auto()).isFalse();
        assertThat(decision.ask()).isTrue();
        assertThat(decision.reason()).contains("sınırlandı").contains("insansız");

        // And the policy screen shows what will really happen, not what the row says.
        assertThat(engine.effectivePolicies()).anySatisfy(policy -> {
            assertThat(policy.toolName()).isEqualTo("jira.deleteIssue");
            assertThat(policy.mode()).isEqualTo(PolicyMode.ASK);
            assertThat(policy.overridden()).isTrue();
        });
    }

    @Test
    void effectivePoliciesCoverEveryRegisteredTool() {
        policies.save(new ToolPolicy("jira", "jira.updateIssue", PolicyMode.AUTO));
        List<PolicyEngine.EffectivePolicy> effective = engine.effectivePolicies();

        assertThat(effective).hasSize(4);
        assertThat(effective).anySatisfy(policy -> {
            assertThat(policy.toolName()).isEqualTo("jira.deleteIssue");
            assertThat(policy.mode()).isEqualTo(PolicyMode.FORBIDDEN);
            assertThat(policy.overridden()).isFalse();
        });
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
