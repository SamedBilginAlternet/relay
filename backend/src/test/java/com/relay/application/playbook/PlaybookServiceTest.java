package com.relay.application.playbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Connection;
import com.relay.domain.Run;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A playbook is only useful if it adapts to what the user actually connected: the morning
 * round-up on a Jira-only install should still run, minus the parts it cannot do — and a
 * playbook that cannot do its core job should not pretend it can.
 */
class PlaybookServiceTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private record Rig(PlaybookService playbooks, TestDoubles.InMemoryRunRepository runs) {
    }

    private Rig rig(String... connectedProviders) {
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new JiraTool.SearchIssues("replay", FIXTURES),
                new JiraTool.CreateIssue("replay", FIXTURES),
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new com.relay.infrastructure.tools.GmailTool.ListToday("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.GmailTool.Search("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.GmailTool.CreateDraft("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.CalendarTool.ListToday(
                        "replay", FIXTURES, null, "Europe/Istanbul"),
                new com.relay.infrastructure.tools.CalendarCreateEventTool(
                        "replay", FIXTURES, null, "Europe/Istanbul"),
                new SlackTool.PostMessage("replay", FIXTURES),
                new com.relay.infrastructure.tools.SheetsTool.AppendRow("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.SheetsTool.ReadRange("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.HrLogLeaveTool("replay", FIXTURES, null),
                new com.relay.infrastructure.tools.DocsCreateDocumentTool("replay", FIXTURES, null)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        for (String provider : connectedProviders) {
            connections.save(Connection.of(provider, Map.of("token", "x"), clock.now()));
        }
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        LlmClient llm = new StubLlmClient(tools);
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, connections, journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService runService = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
        return new Rig(new PlaybookService(tools, connections, runService), runs);
    }

    @Test
    void a_playbook_drops_the_steps_whose_provider_is_missing() {
        Run run = rig("jira").playbooks().start("gunun-ozeti", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("jira.listMyIssues");
    }

    @Test
    void the_same_playbook_keeps_them_when_everything_is_connected() {
        Run run = rig("jira", "github", "slack").playbooks().start("gunun-ozeti", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("jira.listMyIssues", "github.listMyPullRequests", "slack.postMessage");
    }

    /** Mail is the whole point of that flow; without Gmail the button must not run. */
    @Test
    void a_playbook_missing_a_required_provider_refuses_to_start() {
        Rig rig = rig("jira", "slack");

        assertThatThrownBy(() -> rig.playbooks().start("maili-tickete-cevir", 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("google");
    }

    @Test
    void the_catalogue_says_what_is_runnable_and_what_is_missing() {
        List<Map<String, Object>> catalogue = rig("jira").playbooks().describeAll();

        Map<String, Object> morning = catalogue.stream()
                .filter(item -> "gunun-ozeti".equals(item.get("id")))
                .findFirst().orElseThrow();
        assertThat(morning.get("runnable")).isEqualTo(true);

        Map<String, Object> mail = catalogue.stream()
                .filter(item -> "maili-tickete-cevir".equals(item.get("id")))
                .findFirst().orElseThrow();
        assertThat(mail.get("runnable")).isEqualTo(false);
        assertThat(mail.get("missing").toString()).contains("google");
    }

    /**
     * "Toplantıya katılmadan önce şuna bak" on a workspace with Google and nothing else.
     * The Jira search is the step that can be missing, so what is left still runs — including
     * the follow-up meeting, which rides the same connection the calendar read does.
     */
    @Test
    void meeting_prep_drops_the_search_its_workspace_cannot_do() {
        Run run = rig("google").playbooks().start("toplanti-hazirligi", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("calendar.listToday", "gmail.search", "docs.createDocument",
                        "calendar.createEvent");
    }

    /** Without a calendar there is no meeting to prepare for — the button must not run. */
    @Test
    void meeting_prep_without_a_calendar_refuses_to_start() {
        Rig rig = rig("jira");

        assertThatThrownBy(() -> rig.playbooks().start("toplanti-hazirligi", 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("google");
    }

    /**
     * Reading first, writing last, and the write is the only thing that stops on a human.
     *
     * <p>This flow used to be entirely reads and the test used to say so. It ends in two
     * proposals now — the note document the preparation lands in, then the follow-up
     * meeting — and the ordering is the point: three reads run to the end by themselves,
     * and the first step that writes anything (the doc) is where the run waits at the gate,
     * before anything reaches other people's calendars.
     */
    @Test
    void meeting_prep_reads_first_and_stops_only_on_the_write() {
        Run run = rig("google", "jira").playbooks().start("toplanti-hazirligi", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("calendar.listToday", "jira.searchIssues", "gmail.search",
                        "docs.createDocument", "calendar.createEvent");
        assertThat(run.status().wire()).isEqualTo("awaiting_approval");
    }

    /** Steps are seeded, not planned — but a write still stops on the human. */
    @Test
    void a_playbook_write_still_waits_for_approval() {
        Run run = rig("jira", "slack").playbooks().start("blocker-taramasi", 1.0);

        assertThat(run.status().wire()).isEqualTo("awaiting_approval");
        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("jira.searchIssues", "slack.postMessage");
    }

    /**
     * The blocker scan ends twice: Slack is what the team reads today, the spreadsheet row is
     * what somebody reads in a month. The row rides the google connection, so a workspace that
     * never connected Google simply does not get that step — see the test above, where the
     * same playbook comes back two steps long.
     */
    @Test
    void the_blocker_scan_writes_its_row_when_the_workspace_has_a_sheet() {
        Run run = rig("jira", "slack", "google").playbooks().start("blocker-taramasi", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("jira.searchIssues", "slack.postMessage", "sheets.appendRow");
        assertThat(run.status().wire()).isEqualTo("awaiting_approval");
    }

    /**
     * The read→write chain the sheet gains with {@code sheets.readRange}: the rows the
     * blocker scan has been appending finally get read back, and only the Slack write stops
     * on a human. The read is REQUIRED — without the sheet there is nothing to digest — so
     * a workspace that never connected Google does not see this playbook as runnable.
     */
    @Test
    void the_sheet_digest_reads_the_rows_and_stops_only_on_the_slack_write() {
        Run run = rig("google", "slack").playbooks().start("tablo-ozeti", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("sheets.readRange", "slack.postMessage");
        assertThat(run.status().wire()).isEqualTo("awaiting_approval");
    }

    /**
     * The HR story is a flow over the tools a small company's HR actually runs on, not a
     * provider (#169). Everything it needs is one Google connection plus the mailbox, so a
     * workspace with Google alone gets the whole flow — and every write in it is a step
     * that stops at its own gate, which the awaiting status at the end asserts.
     *
     * <p>The ledger row is {@code hr.logLeave} since #171 — the purpose-shaped wrapper
     * over the same append endpoint, riding the same google connection, which is why the
     * flow still runs on Google alone.
     */
    @Test
    void the_leave_flow_runs_on_google_alone_and_stops_on_its_writes() {
        Run run = rig("google").playbooks().start("izin-talepleri", 1.0);

        assertThat(run.steps()).extracting(step -> step.toolName())
                .containsExactly("gmail.search", "calendar.createEvent",
                        "hr.logLeave", "gmail.createDraft");
        assertThat(run.status().wire()).isEqualTo("awaiting_approval");
    }
}
