package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolResult;
import com.relay.domain.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The schema is the gate in front of every provider call — if it lets a bad parameter set
 * through, a hallucination reaches GitHub or Jira. These tests hold that gate shut.
 */
class ToolSchemaTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    static List<Tool> newTools() {
        return List.of(
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new GitHubTool.ListMyIssues("replay", FIXTURES),
                new GitHubTool.AddComment("replay", FIXTURES),
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new JiraTool.CreateIssue("replay", FIXTURES),
                new JiraTool.GetComments("replay", FIXTURES),
                new GmailTool.ListToday("replay", FIXTURES, null),
                new GmailTool.GetMessage("replay", FIXTURES, null),
                new GmailTool.Search("replay", FIXTURES, null),
                new GmailTool.CreateDraft("replay", FIXTURES, null),
                new CalendarTool.ListToday("replay", FIXTURES, null, "Europe/Istanbul"),
                new CalendarUpcomingTool("replay", FIXTURES, null, "Europe/Istanbul"),
                new CalendarCreateEventTool("replay", FIXTURES, null, "Europe/Istanbul"),
                new SheetsTool.AppendRow("replay", FIXTURES, null),
                new NotionTool.CreatePage("replay", FIXTURES));
    }

    @ParameterizedTest
    @MethodSource("newTools")
    void everyToolDeclaresAWellFormedObjectSchema(Tool tool) {
        JsonNode schema = tool.schema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.has("required")).isTrue();
        assertThat(tool.name()).contains(".");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.risk()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("newTools")
    void everyToolHasAReplayFixtureSoTheDemoNeverNeedsAnAccount(Tool tool) {
        assertThat(FIXTURES.has(tool.name())).as("fixture for %s", tool.name()).isTrue();
    }

    @Test
    void riskLevelsMatchTheBriefSpec() {
        assertThat(new GitHubTool.ListMyPullRequests("replay", FIXTURES).risk()).isEqualTo(RiskLevel.READ);
        assertThat(new GitHubTool.ListMyIssues("replay", FIXTURES).risk()).isEqualTo(RiskLevel.READ);
        assertThat(new GitHubTool.AddComment("replay", FIXTURES).risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(new JiraTool.ListMyIssues("replay", FIXTURES).risk()).isEqualTo(RiskLevel.READ);
        assertThat(new JiraTool.CreateIssue("replay", FIXTURES).risk()).isEqualTo(RiskLevel.WRITE);
        // WRITE means the approval gate opens by default (ARCHITECTURE §6).
        assertThat(new JiraTool.CreateIssue("replay", FIXTURES).risk().defaultMode().wire()).isEqualTo("ask");
        // Writing an event onto somebody's calendar is a WRITE and nothing more: DESTRUCTIVE
        // is forbidden by default, and a tool registered there could never run at all.
        Tool event = new CalendarCreateEventTool("replay", FIXTURES, null, "Europe/Istanbul");
        assertThat(event.risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(event.risk().defaultMode().wire()).isEqualTo("ask");
        Tool row = new SheetsTool.AppendRow("replay", FIXTURES, null);
        assertThat(row.risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(row.risk().defaultMode().wire()).isEqualTo("ask");
    }

    @Test
    void googleToolsShareTheGoogleConnection() {
        assertThat(new GmailTool.ListToday("replay", FIXTURES, null).provider()).isEqualTo("google");
        assertThat(new CalendarTool.ListToday("replay", FIXTURES, null, "UTC").provider()).isEqualTo("google");
        // sheets.* is a tool namespace, not a connection: the row rides the same grant.
        assertThat(new SheetsTool.AppendRow("replay", FIXTURES, null).provider()).isEqualTo("google");
        assertThat(new GitHubTool.AddComment("replay", FIXTURES).provider()).isEqualTo("github");
    }

    @Test
    void githubAddCommentRejectsIncompleteParameters() {
        Tool tool = new GitHubTool.AddComment("replay", FIXTURES);

        ObjectNode missing = Json.object();
        missing.put("repo", "acme/payments");
        assertThat(SchemaValidator.validate(tool.schema(), missing).valid()).isFalse();

        ObjectNode wrongType = Json.object();
        wrongType.put("repo", "acme/payments");
        wrongType.put("number", "not-a-number");
        wrongType.put("body", "hi");
        assertThat(SchemaValidator.validate(tool.schema(), wrongType).valid()).isFalse();

        ToolResult rejected = tool.execute(missing, null);
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.error()).contains("invalid params");
        assertThat(rejected.mode()).isEqualTo("rejected");
    }

    @Test
    void jiraCreateIssueRequiresProjectAndSummary() {
        Tool tool = new JiraTool.CreateIssue("replay", FIXTURES);

        assertThat(SchemaValidator.validate(tool.schema(), Json.object()).valid()).isFalse();

        ObjectNode tooShort = Json.object();
        tooShort.put("projectKey", "KAN");
        tooShort.put("summary", "no");
        assertThat(SchemaValidator.validate(tool.schema(), tooShort).valid()).isFalse();

        ObjectNode good = Json.object();
        good.put("projectKey", "KAN");
        good.put("issueType", "Bug");
        good.put("summary", "Ödeme servisi staging'de 502 dönüyor");
        good.put("description", "Ayşe'nin mailinden açıldı.");
        assertThat(SchemaValidator.validate(tool.schema(), good).valid()).isTrue();

        ToolResult result = tool.execute(good, null);
        assertThat(result.ok()).isTrue();
        assertThat(result.mode()).isEqualTo("replay");
        assertThat(result.data().path("issueKey").asText()).startsWith("KAN-");
        assertThat(result.data().path("summary").asText()).contains("502");
    }

    @Test
    void githubReadToolsReplayIntoTheNormalisedShape() {
        ToolResult pulls = new GitHubTool.ListMyPullRequests("replay", FIXTURES)
                .execute(Json.object(), null);
        assertThat(pulls.ok()).isTrue();
        assertThat(pulls.data().path("pullRequests")).isNotEmpty();
        assertThat(pulls.data().path("pullRequests").get(0).path("repo").asText()).contains("/");
        assertThat(pulls.data().path("pullRequests").get(0).path("reason").asText())
                .isIn("review_requested", "author");

        ToolResult issues = new GitHubTool.ListMyIssues("replay", FIXTURES).execute(Json.object(), null);
        assertThat(issues.ok()).isTrue();
        assertThat(issues.data().path("issues")).isNotEmpty();
    }

    @Test
    void jiraListMyIssuesReplaysIssuesWithStatus() {
        ToolResult result = new JiraTool.ListMyIssues("replay", FIXTURES).execute(Json.object(), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("issues")).isNotEmpty();
        assertThat(result.data().path("issues").get(0).path("fields").path("status").path("name").asText())
                .isNotBlank();
    }

    @Test
    void gmailAndCalendarReplayWithoutAnyGoogleCredentials() {
        ToolResult mails = new GmailTool.ListToday("replay", FIXTURES, null).execute(Json.object(), null);
        assertThat(mails.ok()).isTrue();
        assertThat(mails.data().path("messages")).isNotEmpty();

        ObjectNode params = Json.object();
        params.put("messageId", "18f2c9a10b3d4e01");
        ToolResult message = new GmailTool.GetMessage("replay", FIXTURES, null).execute(params, null);
        assertThat(message.ok()).isTrue();
        assertThat(message.data().path("id").asText()).isEqualTo("18f2c9a10b3d4e01");
        assertThat(message.data().path("body").asText()).isNotBlank();

        ObjectNode search = Json.object();
        search.put("query", "from:trendyol newer_than:7d");
        ToolResult found = new GmailTool.Search("replay", FIXTURES, null).execute(search, null);
        assertThat(found.ok()).isTrue();
        assertThat(found.data().path("messages")).isNotEmpty();
        // The replayed answer echoes the query it was asked for.
        assertThat(found.data().path("query").asText()).isEqualTo("from:trendyol newer_than:7d");
        // A search without a query is a search over the whole mailbox — rejected at the gate.
        assertThat(new GmailTool.Search("replay", FIXTURES, null).execute(Json.object(), null).ok())
                .isFalse();

        // A draft with no recipient, no subject or no text is not a draft.
        assertThat(new GmailTool.CreateDraft("replay", FIXTURES, null).execute(Json.object(), null).ok())
                .isFalse();
        ObjectNode draft = Json.object();
        draft.put("to", "ayse@alterteam.dev");
        draft.put("subject", "Re: Ödeme servisi staging'de patlıyor");
        draft.put("body", "Bakıyorum, 14:00'ten önce dönerim.");
        ToolResult drafted = new GmailTool.CreateDraft("replay", FIXTURES, null).execute(draft, null);
        assertThat(drafted.ok()).isTrue();
        assertThat(drafted.data().path("sent").asBoolean(true)).isFalse();
        assertThat(drafted.data().path("subject").asText()).contains("patlıyor");

        ToolResult events = new CalendarTool.ListToday("replay", FIXTURES, null, "Europe/Istanbul")
                .execute(Json.object(), null);
        assertThat(events.ok()).isTrue();
        assertThat(events.data().path("events")).isNotEmpty();

        // "Yarın toplantım var mı" needs a window that outlives today — see CalendarUpcomingTool.
        ToolResult upcoming = new CalendarUpcomingTool("replay", FIXTURES, null, "Europe/Istanbul")
                .execute(Json.object(), null);
        assertThat(upcoming.ok()).isTrue();
        assertThat(upcoming.data().path("events")).isNotEmpty();
    }
}
