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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Building a brief calls five providers and spends two model turns. Measured on the
 * live box on 2026-08-01: 3.6s when Groq answered, 5.4s when it answered slowly, and
 * 14.3s when all seven Groq keys were at their daily token wall and the request fell
 * through to the paid tier — where the answer was then rejected by the digest guard for
 * being written in English, so the fourteen seconds bought nothing at all.
 *
 * <p>For every one of those seconds Bugün showed a skeleton, and the thing being waited
 * for was a summary of a day that had not changed while the reader waited. A brief past
 * its TTL is handed over at once now and rebuilt behind the reader.
 *
 * <p>Three claims, and each one is a way this could quietly stop being true: the reader
 * is not made to wait, the rebuild does happen, and Yenile is still a real refresh rather
 * than a third name for the cache.
 */
class BriefStaleWhileRevalidateTest {

    /** Advances a millisecond per read, so two builds cannot agree by luck. */
    private static class TickingClock implements Clock {
        private Instant now = Instant.parse("2026-08-01T02:25:52Z");

        void jump(Duration by) {
            now = now.plus(by);
        }

        @Override
        public synchronized Instant now() {
            now = now.plusMillis(1);
            return now;
        }
    }

    /** Counts trips to the provider, and can be held inside one. */
    private static class GatedTool implements Tool {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean gate;

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
            if (gate) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var data = Json.object();
            data.putArray("issues");
            return ToolResult.ok(data, 1, "replay");
        }
    }

    private BriefService service(ToolRegistry registry, Clock clock, java.util.concurrent.Executor pool) {
        StubLlmClient llm = new StubLlmClient(registry);
        return new BriefService(registry, new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, registry), new DigestService(llm), llm,
                clock, pool, Duration.ofSeconds(8), Duration.ofSeconds(180),
                "Europe/Istanbul", "RELAY");
    }

    /**
     * The one that matters. The second read happens after the TTL has passed and while the
     * rebuild is stuck inside the provider — if it waited for that rebuild it would be the
     * fourteen seconds this change exists to remove.
     */
    @Test
    void a_reader_arriving_after_the_ttl_is_answered_without_waiting_for_the_rebuild()
            throws Exception {
        GatedTool jira = new GatedTool();
        TickingClock clock = new TickingClock();
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            BriefService brief = service(new ToolRegistryImpl(List.of(jira)), clock, pool);

            Map<String, Object> first = brief.brief();
            assertThat(first.get("cached")).isEqualTo(false);
            assertThat(first.get("stale")).isEqualTo(false);

            // Past the TTL, and the rebuild will hang inside the provider.
            jira.gate = true;
            clock.jump(Duration.ofSeconds(200));

            Map<String, Object> second = brief.brief();

            // Answered from the last good brief — same build, marked as old.
            assertThat(second.get("generatedAt")).isEqualTo(first.get("generatedAt"));
            assertThat(second.get("cached")).isEqualTo(true);
            assertThat(second.get("stale")).isEqualTo(true);
            assertThat(second.get("cachedAt")).isNotNull();

            // And the rebuild really was started, not merely promised.
            assertThat(jira.started.await(5, TimeUnit.SECONDS)).isTrue();
            jira.release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    /** A brief inside its TTL is the same answer it always was, and does not rebuild. */
    @Test
    void a_fresh_brief_is_served_without_touching_the_providers_again() {
        GatedTool jira = new GatedTool();
        TickingClock clock = new TickingClock();
        BriefService brief = service(new ToolRegistryImpl(List.of(jira)), clock, Runnable::run);

        brief.brief();
        Map<String, Object> second = brief.brief();

        assertThat(jira.calls.get()).isEqualTo(1);
        assertThat(second.get("cached")).isEqualTo(true);
        assertThat(second.get("stale")).isEqualTo(false);
    }

    /**
     * Yenile means "I do not believe this is current". Answering it out of the cache would
     * answer a different question, so the press still waits for a real build.
     */
    @Test
    void pressing_yenile_still_waits_for_a_new_brief() {
        GatedTool jira = new GatedTool();
        TickingClock clock = new TickingClock();
        BriefService brief = service(new ToolRegistryImpl(List.of(jira)), clock, Runnable::run);

        Map<String, Object> first = brief.brief();
        clock.jump(Duration.ofSeconds(200));
        Map<String, Object> forced = brief.brief(true);

        assertThat(jira.calls.get()).isEqualTo(2);
        assertThat(forced.get("cached")).isEqualTo(false);
        assertThat(forced.get("stale")).isEqualTo(false);
        assertThat(forced.get("generatedAt")).isNotEqualTo(first.get("generatedAt"));
    }
}
