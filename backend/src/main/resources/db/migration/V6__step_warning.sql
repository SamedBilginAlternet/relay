-- Relay — "this write targets a surface the goal never named".
--
-- Run 85f1b3be (2026-08-01): a goal that named the Notion decision log and quoted
-- a note to append came out of the planner as one step — jira.updateIssue, status
-- Done, KAN-32 — because the planner keyword-matched the note's own payload. The
-- gate showed a plausible Jira write and nothing else; it was approved and the
-- record wrongly closed. The deterministic coverage check now writes its one
-- sentence onto the offending step, and the gate draws it in amber.
--
-- A column rather than a journal line only, because the step is what the approving
-- human reads, and approval is a separate request: the run is read back from the
-- database between planning and the decision, so the warning has to survive the
-- round trip or the gate shows a clean step again.
--
-- Nullable: most steps never earn one, and every step that already exists was
-- planned before the check ran.

alter table steps
    add column warning text;
