package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Connection;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Live, the priority lane was fifteen mails and nothing else: the classifier reads only the
 * first handful of items, and the inbox was concatenated in front of everything. The
 * assigned Jira issue and six pull requests never reached it — the one screen that is meant
 * to say "what is on you today" could not see the work that was actually on him.
 */
class PriorityMixTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private List<Tool> tools() {
        return List.of(
                new GmailTool.ListToday("replay", FIXTURES, null),
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new GitHubTool.ListMyIssues("replay", FIXTURES),
                new com.relay.infrastructure.tools.CalendarTool.ListToday("replay", FIXTURES, null, "Europe/Istanbul"));
    }

    private Map<String, Object> brief() {
        ToolRegistry registry = new ToolRegistryImpl(tools());
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        for (String provider : List.of("google", "jira", "github")) {
            connections.save(Connection.of(provider, Map.of("token", "x"), clock.now()));
        }
        StubLlmClient llm = new StubLlmClient(registry);
        BriefService service = new BriefService(registry, connections,
                new InsightService(llm, registry), new DigestService(llm), llm, clock, Runnable::run,
                Duration.ofSeconds(8), Duration.ofSeconds(60), "Europe/Istanbul", "RELAY");
        return service.brief(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_priority_lane_is_not_all_mail() {
        List<Map<String, Object>> priority = (List<Map<String, Object>>) brief().get("priority");

        assertThat(priority).isNotEmpty();
        assertThat(priority).extracting(card -> card.get("source"))
                .as("every source that has work must be able to reach the lane")
                .contains("gmail", "jira", "github");
    }
}
