-- Relay — "the human typed these parameters".
--
-- Approval and execution are two different requests: the run is written at the
-- end of the first and read back at the start of the second. A flag that lived
-- only in memory would therefore be gone by the time it matters, and the
-- specialist would regenerate — that is, overwrite — the value the person had
-- just corrected on the screen.
--
-- Default false: every run that already exists was never edited by hand.

alter table steps
    add column params_locked boolean not null default false;
