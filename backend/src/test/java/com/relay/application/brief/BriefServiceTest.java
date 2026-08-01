package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmClient;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The whole point of the brief endpoint: one missing integration greys out one card and
 * nothing else. Also guards the wire contract the frontend renders against.
 */
class BriefServiceTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final List<String> SECTIONS = List.of("inbox", "work", "code", "calendar");

    private BriefService serviceWith(List<Tool> tools, TestDoubles.FixedClock clock) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        return serviceWith(registry, clock, new StubLlmClient(registry));
    }

    private BriefService serviceWith(ToolRegistry registry, TestDoubles.FixedClock clock, LlmClient llm) {
        return serviceWith(registry, clock, llm, new TestDoubles.InMemoryConnectionRepository());
    }

    private BriefService serviceWith(ToolRegistry registry, TestDoubles.FixedClock clock, LlmClient llm,
                                     ConnectionRepository connections) {
        return new BriefService(registry, connections,
                new InsightService(llm, registry), new DigestService(llm), llm, clock, Runnable::run,
                Duration.ofSeconds(8), Duration.ofSeconds(60), "Europe/Istanbul", "RELAY");
    }

    /**
     * The brief on a real thread pool, which is the only way its two headline properties can
     * be observed at all: with {@code Runnable::run} every call completes on the calling
     * thread before {@code completeOnTimeout} is ever armed, so the timeout branch is
     * unreachable and "parallel" is unmeasurable.
     *
     * <p>Virtual threads, same as production ({@code ApplicationConfig}), and a timeout the
     * test can outrun — the point is which branch fires, not how long anyone waits.
     */
    private BriefService parallelService(List<Tool> tools, Duration toolTimeout) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        LlmClient llm = new StubLlmClient(registry);
        return new BriefService(registry, new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, registry), new DigestService(llm), llm,
                new TestDoubles.FixedClock(), Executors.newVirtualThreadPerTaskExecutor(),
                toolTimeout, Duration.ofSeconds(60), "Europe/Istanbul", "RELAY");
    }

    /**
     * A provider that takes its time. Answers correctly — eventually — which is exactly the
     * case the timeout exists for: not a provider that is down, one that is slow.
     */
    private static class SlowTool implements Tool {
        private final String name;
        private final long delayMs;

        SlowTool(String name, long delayMs) {
            this.name = name;
            this.delayMs = delayMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "answers after " + delayMs + " ms";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.ok(Json.object(), delayMs, "replay");
        }
    }

    private List<Tool> everything() {
        return new ArrayList<>(List.of(
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new JiraTool.CreateIssue("replay", FIXTURES),
                new JiraTool.AddComment("replay", FIXTURES),
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new GitHubTool.ListMyIssues("replay", FIXTURES),
                new GitHubTool.AddComment("replay", FIXTURES),
                new SlackTool.PostMessage("replay", FIXTURES)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> brief, String name) {
        return (Map<String, Object>) brief.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> section) {
        return (List<Map<String, Object>>) section.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> priority(Map<String, Object> brief) {
        return (List<Map<String, Object>>) brief.get("priority");
    }

    @Test
    void missingIntegrationsAreUnavailableWhileTheRestStillArrives() {
        // No gmail tool and no calendar tool registered at all — the Google seam is not there yet.
        Map<String, Object> brief = serviceWith(everything(), new TestDoubles.FixedClock()).brief();

        assertThat(section(brief, "work").get("status")).isEqualTo("ok");
        assertThat(rows(section(brief, "work"))).isNotEmpty();
        assertThat(section(brief, "work").get("reason")).isNull();
        assertThat(section(brief, "code").get("status")).isEqualTo("ok");
        assertThat(rows(section(brief, "code"))).isNotEmpty();

        assertThat(section(brief, "inbox").get("status")).isEqualTo("unavailable");
        assertThat(rows(section(brief, "inbox"))).isEmpty();
        assertThat(section(brief, "inbox").get("reason")).asString().contains("Gmail");

        assertThat(section(brief, "calendar").get("status")).isEqualTo("unavailable");
        assertThat(brief.get("date")).isNotNull();
    }

    @Test
    void aFailingProviderMarksOnlyItsOwnSectionAsError() {
        List<Tool> tools = everything();
        tools.add(new TestDoubles.FailingTool("gmail.listToday"));

        Map<String, Object> brief = serviceWith(tools, new TestDoubles.FixedClock()).brief();

        assertThat(section(brief, "inbox").get("status")).isEqualTo("error");
        // The raw provider message never reaches the user.
        assertThat(section(brief, "inbox").get("reason")).asString()
                .contains("Gmail").doesNotContain("exploded");
        assertThat(section(brief, "work").get("status")).isEqualTo("ok");
        assertThat(section(brief, "code").get("status")).isEqualTo("ok");
    }

    @Test
    void codeSectionSurvivesOneOfItsTwoToolsFailing() {
        List<Tool> tools = new ArrayList<>(List.of(
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new TestDoubles.FailingTool("github.listMyIssues")));

        Map<String, Object> brief = serviceWith(tools, new TestDoubles.FixedClock()).brief();
        Map<String, Object> code = section(brief, "code");

        assertThat(code.get("status")).isEqualTo("ok");
        assertThat(rows(code)).isNotEmpty();
        assertThat(((Map<?, ?>) code.get("parts")).get("issues")).isEqualTo("error");
    }

    @Test
    void everySectionCarriesTheExactRowShapeTheFrontendRenders() {
        Map<String, Object> brief = serviceWith(everything(), new TestDoubles.FixedClock()).brief();

        for (String name : SECTIONS) {
            Map<String, Object> section = section(brief, name);
            assertThat(section.keySet()).as(name).contains("status", "reason", "items");
            assertThat(section.get("status")).as(name).isIn("ok", "unavailable", "error");
            for (Map<String, Object> row : rows(section)) {
                assertThat(row.keySet()).as(name + " row")
                        .containsExactlyInAnyOrder("id", "title", "subtitle", "meta", "url", "tone");
                assertThat(row.get("title")).asString().isNotBlank();
                assertThat(row.get("tone")).isIn("default", "warn", "danger", "success");
                // Provider internals stay out of the row.
                assertThat(row.keySet()).doesNotContain("ref", "source", "kind");
            }
        }
        // A Jira row reads "KAN-42 <summary>", not a bare summary.
        assertThat(rows(section(brief, "work")).get(0).get("title")).asString().startsWith("KAN-");
        assertThat(rows(section(brief, "work")).get(0).get("meta")).asString().isNotBlank();
    }

    @Test
    void priorityIsAFlatCardArrayWithOnlyRegisteredTools() {
        ToolRegistry registry = new ToolRegistryImpl(everything());
        Map<String, Object> brief = serviceWith(everything(), new TestDoubles.FixedClock()).brief();

        assertThat(priority(brief)).isNotEmpty();
        assertThat(priority(brief)).allSatisfy(card -> {
            assertThat(card.keySet()).contains("id", "source", "title", "from", "kind", "urgency",
                    "summary", "suggestedActions");
            assertThat(card.get("kind")).isIn(InsightService.KINDS.toArray());
            assertThat(card.get("urgency")).isIn(InsightService.URGENCIES.toArray());
            assertThat(card.get("summary")).asString().isNotBlank();
            for (Object action : (List<?>) card.get("suggestedActions")) {
                Map<String, Object> map = asMap(action);
                assertThat(map.keySet()).contains("tool", "label", "params");
                assertThat(registry.find(String.valueOf(map.get("tool")))).isPresent();
            }
        });
        // The highest urgency lands on top.
        assertThat(priority(brief).get(0).get("urgency")).isEqualTo("high");
    }

    /**
     * Live, every "Jira ticket aç" card carried {@code projectKey: RELAY} — the config
     * default — while the connected Jira only had KAN. Approving the card produced a 404
     * instead of a ticket. Nothing in today's brief pointed at a project, so the connection
     * is the only place left that knows.
     */
    @Test
    void aSuggestionFilesAgainstTheConnectedProjectNotTheConfigDefault() {
        // No jira.listMyIssues: the work lane is empty, exactly like the deployed instance.
        ToolRegistry registry = new ToolRegistryImpl(List.of(
                new GmailTool.ListToday("replay", FIXTURES, null),
                new JiraTool.CreateIssue("replay", FIXTURES)));
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        connections.save(Connection.of("jira", Map.of("baseUrl", "https://x.atlassian.net",
                "email", "a@b.c", "apiToken", "t", "projectKey", "kan"), Instant.parse("2026-07-31T20:00:00Z")));

        Map<String, Object> brief = serviceWith(registry, new TestDoubles.FixedClock(),
                new StubLlmClient(registry), connections).brief();

        List<Map<String, Object>> created = priority(brief).stream()
                .flatMap(card -> ((List<?>) card.get("suggestedActions")).stream())
                .map(BriefServiceTest::asMap)
                .filter(action -> "jira.createIssue".equals(action.get("tool")))
                .toList();

        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(action ->
                assertThat(asMap(action.get("params")).get("projectKey")).isEqualTo("KAN"));
    }

    @Test
    void theResultIsCachedAndRefreshBypassesIt() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        BriefService service = serviceWith(everything(), clock);

        Map<String, Object> first = service.brief();
        assertThat(first.get("cached")).isEqualTo(false);

        Map<String, Object> second = service.brief();
        assertThat(second.get("cached")).isEqualTo(true);
        assertThat(second.get("generatedAt")).isEqualTo(first.get("generatedAt"));

        Map<String, Object> forced = service.brief(true);
        assertThat(forced.get("cached")).isEqualTo(false);

        /*
          Past the TTL the reader is still answered out of the cache — marked stale — and
          the rebuild happens behind them. It used to block here, which live meant between
          3.6 and 14.3 seconds of skeleton for a summary of a day that had not changed
          while the reader waited. See BriefStaleWhileRevalidateTest.
        */
        clock.advance(Duration.ofSeconds(61));
        Map<String, Object> old = service.brief();
        assertThat(old.get("cached")).isEqualTo(true);
        assertThat(old.get("stale")).isEqualTo(true);
    }

    /**
     * The stub cannot write a daily summary — it writes filler. Live, that filler
     * ("Adımlar yürütüldü; ayrıntılar zaman çizelgesinde") went out as if it were an
     * insight. Degraded, the field is simply not there.
     */
    @Test
    void noDigestIsWrittenWhileTheModelIsDegraded() {
        Map<String, Object> brief = serviceWith(everything(), new TestDoubles.FixedClock()).brief();

        assertThat(brief).doesNotContainKey("digest");
        // Everything the frontend already renders is untouched.
        assertThat(brief).containsKeys("date", "priority", "inbox", "work", "code", "calendar");
        assertThat(priority(brief)).isNotEmpty();
    }

    /**
     * Same rule as {@code noDigestIsWrittenWhileTheModelIsDegraded}, for the case the flag
     * misses: the client says it is healthy, the keys run out during the call, and the answer
     * comes back from the stub. A summary is only ever written by a real model.
     */
    @Test
    void aDigestThatCameFromTheStubIsDroppedEvenWhenTheClientSaysItIsHealthy() {
        ToolRegistry registry = new ToolRegistryImpl(everything());
        LlmClient sneaky = new LlmClient() {
            @Override
            public com.relay.application.port.LlmResponse complete(
                    com.relay.application.port.LlmRequest request) {
                return new com.relay.application.port.LlmResponse(
                        "{\"summary\":\"Bugün her şey yolunda.\",\"priorities\":[],\"advice\":\"Devam et.\"}",
                        10, 5, 0.0, "stub", true);
            }

            @Override
            public String name() {
                return "sneaky";
            }

            @Override
            public boolean degraded() {
                return false;
            }
        };

        Map<String, Object> brief = serviceWith(registry, new TestDoubles.FixedClock(), sneaky).brief();

        assertThat(brief).doesNotContainKey("digest");
        assertThat(priority(brief)).isNotEmpty();
    }

    @Test
    void digestCarriesASummaryAnOrderAndAdviceWithoutTouchingTheOldFields() {
        ToolRegistry registry = new ToolRegistryImpl(everything());
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                "insight", "{\"insights\":[]}",
                "digest", """
                        {"summary":"Bugün 4 Jira işi ve 3 GitHub kaydı seni bekliyor; ikisi review istiyor.",
                         "priorities":[
                           {"itemId":"jira:KAN-42","why":"Blocked ve sprint sonuna iki gün kaldı."},
                           {"itemId":"jira:MADE-UP","why":"Bu iş hiç gönderilmedi."}],
                         "advice":"Sabahın ilk saatini KAN-42'nin engelini kaldırmaya ayır."}"""));

        Map<String, Object> brief = serviceWith(registry, new TestDoubles.FixedClock(), llm).brief();
        Map<String, Object> digest = asMap(brief.get("digest"));

        assertThat(digest.keySet()).containsExactlyInAnyOrder("summary", "priorities", "advice");
        assertThat(digest.get("summary")).asString().contains("Jira");
        assertThat(digest.get("advice")).asString().isNotBlank();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> priorities = (List<Map<String, Object>>) digest.get("priorities");
        // An id the model invented never reaches the screen.
        assertThat(priorities).extracting(row -> row.get("itemId")).containsExactly("jira:KAN-42");
        assertThat(priorities.get(0).get("why")).asString().isNotBlank();

        // The contract the frontend is built on is exactly as it was.
        assertThat(brief).containsKeys("date", "localDate", "priority", "inbox", "work", "code", "calendar");
        for (String name : SECTIONS) {
            assertThat(section(brief, name).keySet()).as(name).contains("status", "reason", "items");
        }
        assertThat(asMap(brief.get("llm")).get("tokens")).isEqualTo(300L);
    }

    @Test
    void aDigestWithoutASummaryIsDroppedRatherThanShownEmpty() {
        ToolRegistry registry = new ToolRegistryImpl(everything());
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                "insight", "{\"insights\":[]}",
                "digest", "{\"summary\":\"  \",\"advice\":\"Bir şeyler yap.\"}"));

        Map<String, Object> brief = serviceWith(registry, new TestDoubles.FixedClock(), llm).brief();

        assertThat(brief).doesNotContainKey("digest");
    }

    /**
     * The likeliest failure of the whole demo: conference wifi, one provider crawls. The
     * screen must come back without it rather than waiting on it — and the other cards, in
     * every state they can be in, must still be right.
     */
    @Test
    void a_provider_that_never_answers_is_dropped_at_the_timeout() {
        List<Tool> tools = everything();
        tools.add(new SlowTool("gmail.listToday", 3000));
        BriefService service = parallelService(tools, Duration.ofMillis(150));

        long startedAt = System.nanoTime();
        Map<String, Object> brief = service.brief();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(section(brief, "inbox").get("status")).isEqualTo("error");
        assertThat(section(brief, "inbox").get("reason")).asString()
                .isEqualTo("Gmail zamanında yanıt vermedi.");
        assertThat(rows(section(brief, "inbox"))).isEmpty();
        assertThat(elapsedMs).as("the call returned on the timeout, not on the provider")
                .isLessThan(2000);

        // Three states at once, which is the mix a real morning produces: one provider slow,
        // one answering, one not connected at all.
        assertThat(section(brief, "work").get("status")).isEqualTo("ok");
        assertThat(section(brief, "calendar").get("status")).isEqualTo("unavailable");
    }

    @Test
    void the_brief_costs_the_slowest_provider_not_their_sum() {
        BriefService service = parallelService(List.of(
                new SlowTool("gmail.listToday", 200),
                new SlowTool("jira.listMyIssues", 200),
                new SlowTool("github.listMyPullRequests", 200),
                new SlowTool("github.listMyIssues", 200),
                new SlowTool("calendar.listToday", 200)), Duration.ofSeconds(5));

        long startedAt = System.nanoTime();
        Map<String, Object> brief = service.brief();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        for (String name : SECTIONS) {
            assertThat(section(brief, name).get("status")).as(name).isEqualTo("ok");
        }
        // Serially this is a full second. Generous headroom: what would fail here is
        // sequential execution, not a slow machine.
        assertThat(elapsedMs).isLessThan(800);
    }

    /**
     * Nothing came back from anywhere — every provider down at once. The screen still has to
     * render: the insight layer is handed an empty list, {@code DayTally} counts zeros, and
     * no field the frontend reads may be missing.
     */
    @Test
    void a_day_where_every_provider_is_down_still_renders_a_screen() {
        BriefService service = parallelService(List.of(
                new TestDoubles.FailingTool("gmail.listToday"),
                new TestDoubles.FailingTool("jira.listMyIssues"),
                new TestDoubles.FailingTool("github.listMyPullRequests"),
                new TestDoubles.FailingTool("github.listMyIssues"),
                new TestDoubles.FailingTool("calendar.listToday")), Duration.ofSeconds(5));

        Map<String, Object> brief = service.brief();

        assertThat(priority(brief)).isEmpty();
        for (String name : SECTIONS) {
            Map<String, Object> section = section(brief, name);
            assertThat(section.get("status")).as(name).isEqualTo("error");
            assertThat(rows(section)).as(name).isEmpty();
            assertThat(section.get("reason")).as(name).isNotNull();
        }
        Map<String, Object> today = asMap(brief.get("today"));
        assertThat(today.get("headline")).isEqualTo("Bugün seni bekleyen bir şey görünmüyor.");
        assertThat(asMap(today.get("counts")).values()).allSatisfy(count ->
                assertThat(count).isEqualTo(0));
        assertThat(brief.get("date")).isNotNull();
        assertThat(brief.get("localDate")).isNotNull();
        assertThat(asMap(brief.get("llm"))).isNotNull();
    }

    /** One card, two tools: whichever answered wins — and neither answering is one error. */
    @Test
    void both_code_tools_failing_is_still_one_error_status() {
        BriefService service = parallelService(List.of(
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new TestDoubles.FailingTool("github.listMyPullRequests"),
                new TestDoubles.FailingTool("github.listMyIssues")), Duration.ofSeconds(5));

        Map<String, Object> brief = service.brief();
        Map<String, Object> code = section(brief, "code");

        assertThat(code.get("status")).isEqualTo("error");
        assertThat(code.get("reason")).asString().contains("GitHub").doesNotContain("exploded");
        assertThat(rows(code)).isEmpty();
        Map<?, ?> parts = (Map<?, ?>) code.get("parts");
        assertThat(parts.get("pullRequests")).isEqualTo("error");
        assertThat(parts.get("issues")).isEqualTo("error");
        // The rest of the morning is untouched.
        assertThat(section(brief, "work").get("status")).isEqualTo("ok");
    }

    @Test
    void failureReasonsAreTurkishAndLeakNothing() {
        String raw = "ToolCallException: HTTP 401 from api.github.com: "
                + "{\"message\":\"Bad credentials\",\"token\":\"github_pat_secret\"}";
        String reason = BriefService.failureReason("GitHub", raw);

        assertThat(reason).contains("GitHub").contains("401").doesNotContain("github_pat_secret");
        assertThat(BriefService.failureReason("Jira", "HTTP 429 rate limited")).contains("hız sınırına");
        assertThat(BriefService.failureReason("Gmail", "HTTP 503 upstream")).contains("yanıt veremiyor");
        assertThat(BriefService.failureReason("Jira", "java.net.SocketTimeoutException: timeout"))
                .contains("zamanında yanıt vermedi");
    }
}
