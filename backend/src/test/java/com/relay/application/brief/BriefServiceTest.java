package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        return new BriefService(registry, new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, registry), new DigestService(llm), llm, clock, Runnable::run,
                Duration.ofSeconds(8), Duration.ofSeconds(60), "Europe/Istanbul", "RELAY");
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

        clock.advance(Duration.ofSeconds(61));
        assertThat(service.brief().get("cached")).isEqualTo(false);
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
