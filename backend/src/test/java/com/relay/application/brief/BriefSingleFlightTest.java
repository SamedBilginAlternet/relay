package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.Clock;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tokens are the scarcest resource in this product, and the Yenile button takes four or
 * five seconds to answer — so pressing it twice is what a person does, not what an abuser
 * does.
 *
 * <p>It used to buy two full briefs. Live, two concurrent {@code POST /api/brief/refresh}
 * calls spent 5 716 and 5 208 tokens for the same morning, and the two were stamped one
 * millisecond apart — genuinely parallel, neither waiting for the other. Whichever finished
 * last wrote the cache; the other brief was paid for and thrown away.
 */
class BriefSingleFlightTest {

    /**
     * Advances a millisecond every time it is read, the way a real clock does. Two builds
     * cannot then accidentally agree on {@code generatedAt} and pass this test by luck.
     */
    private static class TickingClock implements Clock {
        private Instant now = Instant.parse("2026-08-01T02:25:52Z");

        @Override
        public synchronized Instant now() {
            now = now.plusMillis(1);
            return now;
        }
    }

    /** Counts how many times the brief actually went and fetched, and holds it there. */
    private static class GatedTool implements Tool {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String name() {
            return "jira.listMyIssues";
        }

        @Override
        public String description() {
            return "atanmış kayıtlar";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var data = Json.object();
            data.putArray("issues");
            return ToolResult.ok(data, 1, "replay");
        }
    }

    @Test
    void two_concurrent_refreshes_produce_one_generation() throws Exception {
        GatedTool jira = new GatedTool();
        ToolRegistry registry = new ToolRegistryImpl(List.of(jira));
        StubLlmClient llm = new StubLlmClient(registry);
        BriefService brief = new BriefService(registry,
                new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, registry), new DigestService(llm), llm,
                new TickingClock(), Runnable::run, Duration.ofSeconds(8), Duration.ofSeconds(180),
                "Europe/Istanbul", "RELAY");

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> brief.brief(true));
            assertThat(jira.started.await(5, TimeUnit.SECONDS)).isTrue();

            // The second press lands while the first build is still inside the tool call.
            var second = pool.submit(() -> brief.brief(true));
            Thread.sleep(100);
            jira.release.countDown();

            Map<String, Object> a = first.get(10, TimeUnit.SECONDS);
            Map<String, Object> b = second.get(10, TimeUnit.SECONDS);

            // The whole point: one trip to the providers, one round of model turns.
            assertThat(jira.calls.get()).isEqualTo(1);
            // And it is visible from outside — both callers were answered by the same build.
            assertThat(a.get("generatedAt")).isEqualTo(b.get("generatedAt"));
            assertThat(a.get("cached")).isEqualTo(false);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Single flight is not a second cache: once the shared build is done, the next press
     * of Yenile is a new press and gets a new brief.
     */
    @Test
    void a_refresh_after_the_shared_build_finished_starts_a_new_one() {
        GatedTool jira = new GatedTool();
        jira.release.countDown();
        ToolRegistry registry = new ToolRegistryImpl(List.of(jira));
        StubLlmClient llm = new StubLlmClient(registry);
        BriefService brief = new BriefService(registry,
                new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, registry), new DigestService(llm), llm,
                new TickingClock(), Runnable::run, Duration.ofSeconds(8), Duration.ofSeconds(180),
                "Europe/Istanbul", "RELAY");

        Map<String, Object> first = brief.brief(true);
        Map<String, Object> second = brief.brief(true);

        assertThat(jira.calls.get()).isEqualTo(2);
        assertThat(first.get("generatedAt")).isNotEqualTo(second.get("generatedAt"));
    }
}
