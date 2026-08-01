-- Relay — "which model answered, and what would it have cost on the good one".
--
-- The tier is now chosen by the job (verify/summarize/ask_route go to the small
-- model, everything else to the strong one), so two steps of the same run no
-- longer cost the same per token. A cost figure with no model beside it cannot be
-- read: nobody can tell a cheap step from a step that got the cheap answer.
--
-- premium_cost_usd is the same measured token counts priced at the strong model's
-- rate — the number behind "this run cost X, all-premium it would have cost Y".
-- Nullable, and null means *not derivable*, not zero: the offline stub counts
-- characters rather than tokens and no provider ever billed them, so a step that
-- ran on it has no honest premium figure and says so instead of claiming zero.
--
-- model_tokens is bookkeeping, not a wire field. A step makes several calls and
-- keeps the model of the one that did the most tokens; the calls are spread over
-- two HTTP requests (parameters at the approval gate, execution after the
-- approval) with a database round trip between them, so the high-water mark has
-- to survive that round trip or the last call always wins.
--
-- Every step that already exists ran before the split: no model was recorded and
-- no premium can be reconstructed for it, which is exactly what null says.

alter table steps
    add column model            varchar(128),
    add column model_tokens     bigint not null default 0,
    add column premium_cost_usd double precision;
