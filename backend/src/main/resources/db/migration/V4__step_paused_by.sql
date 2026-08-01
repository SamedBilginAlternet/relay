-- Relay — "which question is this step asking?"
--
-- A parked step meant one of two different things — the policy wants a human on
-- this write, or the run has spent past its ceiling — and the wire shape for both
-- was the same `awaiting_approval`. Approval is a separate request, so by the time
-- it arrives the run has been read back from the database and every in-process
-- memory of why it stopped is gone. All `approve` could still see was "the run is
-- over budget", which is why approving a Slack message also lifted the ceiling.
--
-- Nullable: only a parked step has a reason, and every step that already exists
-- was written before the column did.

alter table steps
    add column paused_by varchar(16);
