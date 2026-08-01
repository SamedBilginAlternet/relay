package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a provider sends back is not what goes on the screen.
 *
 * <p>A tool result travels further than any other value in Relay: onto the audit trail, down
 * the SSE stream into a browser, and into the next step's prompt. Measured on the live
 * stream, one reading step's answer arrived carrying 45 of Atlassian's own REST urls, 22
 * icon urls, 11 {@code expand} lists, 11 {@code statusCategory} trees and two accountIds.
 * Nothing read any of it, and everything carried it.
 *
 * <p>docs/NASIL-CALISIYOR.md §3 already promises a raw provider message is never passed
 * through, "because it can hold a url, a request body or a token". That promise was kept for
 * failures and broken for successes. These tests are the promise, for successes.
 */
class ToolResultProjectionTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    /** Trimmed from the live answer of {@code /rest/api/3/search/jql} on a real board. */
    private static final String RAW_SEARCH = """
            {
              "isLast": true,
              "nextPageToken": "EAoYnIqT2fszIjtwcm9qZWN0IGlzIG5vdCBFTVBUWSBBTkQ",
              "issues": [
                {
                  "id": "10010",
                  "key": "KAN-11",
                  "self": "https://samedbilgin322.atlassian.net/rest/api/3/issue/10010",
                  "expand": "renderedFields,names,schema,operations,editmeta,changelog",
                  "fields": {
                    "summary": "Ödeme servisi staging'de 502 dönüyor",
                    "status": {
                      "self": "https://samedbilgin322.atlassian.net/rest/api/3/status/10000",
                      "name": "Blocked",
                      "id": "10000",
                      "iconUrl": "https://samedbilgin322.atlassian.net/images/icons/statuses/generic.png",
                      "statusCategory": {
                        "self": "https://samedbilgin322.atlassian.net/rest/api/3/statuscategory/2",
                        "id": 2,
                        "key": "new",
                        "colorName": "blue-gray"
                      }
                    },
                    "priority": {
                      "self": "https://samedbilgin322.atlassian.net/rest/api/3/priority/1",
                      "iconUrl": "https://samedbilgin322.atlassian.net/images/icons/priorities/highest.svg",
                      "name": "Highest",
                      "id": "1"
                    },
                    "assignee": {
                      "self": "https://samedbilgin322.atlassian.net/rest/api/3/user?accountId=5b10a2",
                      "accountId": "5b10a2844c20165700ede21g",
                      "avatarUrls": {
                        "48x48": "https://secure.gravatar.com/avatar/abc?d=https%3A%2F%2Favatar.png"
                      },
                      "displayName": "Deniz Arslan",
                      "active": true
                    }
                  }
                }
              ]
            }
            """;

    @Test
    void a_tool_result_never_carries_provider_internal_urls() {
        JiraTool.SearchIssues search = new JiraTool.SearchIssues("replay", FIXTURES);

        JsonNode projected = search.project(Json.parse(RAW_SEARCH));
        String wire = projected.toString();

        assertThat(wire)
                .as("what Jira says about itself is Jira's business, not the screen's")
                .doesNotContain("self")
                .doesNotContain("expand")
                .doesNotContain("iconUrl")
                .doesNotContain("avatarUrls")
                .doesNotContain("statusCategory")
                .doesNotContain("nextPageToken")
                .doesNotContain("atlassian.net")
                .doesNotContain("accountId");
        JsonNode issue = projected.path("issues").get(0);
        assertThat(issue.path("key").asText()).isEqualTo("KAN-11");
        assertThat(issue.path("fields").path("summary").asText())
                .isEqualTo("Ödeme servisi staging'de 502 dönüyor");
        assertThat(issue.path("fields").path("status").path("name").asText()).isEqualTo("Blocked");
        assertThat(issue.path("fields").path("assignee").path("displayName").asText())
                .isEqualTo("Deniz Arslan");
    }

    /** The rich-text body of a comment is markup around one sentence. The sentence is the point. */
    @Test
    void a_comment_arrives_as_the_sentence_somebody_wrote() {
        JiraTool.AddComment addComment = new JiraTool.AddComment("replay", FIXTURES);
        JsonNode raw = Json.parse("""
                {
                  "self": "https://samedbilgin322.atlassian.net/rest/api/3/issue/10010/comment/10241",
                  "id": "10241",
                  "issueKey": "KAN-11",
                  "author": {
                    "accountId": "5b10a2844c20165700ede21g",
                    "avatarUrls": {"48x48": "https://secure.gravatar.com/avatar/abc"},
                    "displayName": "Relay Bot"
                  },
                  "body": {"type": "doc", "version": 1, "content": [
                    {"type": "paragraph", "content": [{"type": "text", "text": "Gateway ekibine ticket açıldı."}]}]},
                  "created": "2026-07-31T09:14:00.000+0300",
                  "jsdPublic": true
                }
                """);

        JsonNode projected = addComment.project(raw);

        assertThat(projected.path("body").asText()).isEqualTo("Gateway ekibine ticket açıldı.");
        assertThat(projected.path("issueKey").asText()).isEqualTo("KAN-11");
        assertThat(projected.path("author").path("displayName").asText()).isEqualTo("Relay Bot");
        assertThat(projected.toString()).doesNotContain("self").doesNotContain("avatarUrls")
                .doesNotContain("jsdPublic");
    }

    /**
     * Replay and live have to answer in the same shape, or the demo tests one product and
     * the customer gets another. Projecting a recorded answer must leave it exactly as it
     * was — a projection that changes the fixture is a projection that is wrong.
     */
    @Test
    void a_projection_leaves_a_recorded_answer_exactly_as_it_found_it() {
        List<AbstractTool> tools = List.of(
                new JiraTool.SearchIssues("replay", FIXTURES),
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new JiraTool.GetIssue("replay", FIXTURES),
                new JiraTool.GetComments("replay", FIXTURES),
                new JiraTool.CreateIssue("replay", FIXTURES),
                new JiraTool.UpdateIssue("replay", FIXTURES),
                new JiraTool.AddComment("replay", FIXTURES),
                new SlackTool.ListChannels("replay", FIXTURES),
                new SlackTool.PostMessage("replay", FIXTURES),
                new GitHubTool.CreateIssue("replay", FIXTURES),
                new CalendarCreateEventTool("replay", FIXTURES, null, "Europe/Istanbul"),
                new SheetsTool.AppendRow("replay", FIXTURES, null),
                new SheetsTool.ReadRange("replay", FIXTURES, null),
                new NotionTool.CreatePage("replay", FIXTURES));

        for (AbstractTool tool : tools) {
            JsonNode recorded = FIXTURES.load(tool.name(), Json.object());
            assertThat(tool.project(recorded))
                    .as("%s replays a different shape than it answers with", tool.name())
                    .isEqualTo(recorded);
        }
    }
}
