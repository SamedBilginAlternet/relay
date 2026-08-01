import { expect, it } from 'vitest';
import type { Run, RunEvent, Step } from '../types/api';
import { applyEvent, mergeRun } from './applyEvent';

/**
 * The stream replays, and a replay may not rewrite history.
 *
 * <p>The backend hands every new subscriber the run's whole backlog — up to four hundred
 * frames — on purpose, so somebody who opens a flow late still sees it from the start. The
 * reducer was written on the opposite assumption and stamped `Date.now()` on whatever
 * arrived. One dropped connection therefore replayed the run onto itself, reset every step's
 * start and finish to the moment of the reconnect, and moved the run's end time with them:
 * a flow that took four minutes showed six steps of ~0 ms. The timings are part of what the
 * product claims to prove, and they were erased by a blip in the wifi.
 */

const STARTED = '2026-07-31T09:00:00.000Z';
const FINISHED = '2026-07-31T09:00:12.000Z';
const LATER = '2026-07-31T11:30:00.000Z';

function step(overrides: Partial<Step> = {}): Step {
  return {
    id: 'step-1',
    ordinal: 1,
    title: 'Blocker kayıtlarını bul',
    role: 'jira-agent',
    toolName: 'jira.searchIssues',
    params: { jql: 'labels = blocker' },
    status: 'pending',
    decision: null,
    pausedBy: null,
    rejectReason: null,
    result: null,
    error: null,
    tokens: 0,
    costUsd: 0,
    startedAt: null,
    finishedAt: null,
    ...overrides,
  };
}

function run(overrides: Partial<Run> = {}): Run {
  return {
    id: 'run-1',
    goal: 'Blocker kayıtlarını bul',
    status: 'running',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    steps: [step()],
    messages: [],
    createdAt: STARTED,
    finishedAt: null,
    ...overrides,
  };
}

/** The frames the backend emits for one step running to completion. */
function story(): RunEvent[] {
  return [
    { type: 'run.planned', steps: [step()] },
    { type: 'step.started', stepId: 'step-1' },
    {
      type: 'step.finished',
      stepId: 'step-1',
      status: 'done',
      result: { ok: true },
      tokens: 120,
      costUsd: 0.002,
      step: step({ status: 'done', startedAt: STARTED, finishedAt: FINISHED }),
    },
    { type: 'run.finished', status: 'done', finishedAt: FINISHED },
  ];
}

function play(from: Run, events: RunEvent[], clock: string): Run {
  return events.reduce((state, event) => applyEvent(state, event, clock), from);
}

it('applying_the_same_event_twice_changes_nothing', () => {
  const finished: RunEvent = {
    type: 'step.finished',
    stepId: 'step-1',
    status: 'done',
    result: { ok: true },
    tokens: 120,
    costUsd: 0.002,
    step: step({ status: 'done', startedAt: STARTED, finishedAt: FINISHED }),
  };

  const once = applyEvent(run(), finished, STARTED);
  const twice = applyEvent(once, finished, LATER);

  expect(twice).toEqual(once);
});

it('a_reconnect_does_not_reset_the_step_timings', () => {
  const first = play(run(), story(), STARTED);
  expect(first.steps[0]?.startedAt).toBe(STARTED);
  expect(first.steps[0]?.finishedAt).toBe(FINISHED);

  // Two and a half hours later the connection drops and the whole backlog arrives again.
  const afterReplay = play(first, story(), LATER);

  expect(afterReplay.steps[0]?.startedAt).toBe(STARTED);
  expect(afterReplay.steps[0]?.finishedAt).toBe(FINISHED);
  expect(afterReplay.steps[0]?.status).toBe('done');
  expect(afterReplay.finishedAt).toBe(FINISHED);
  expect(afterReplay.status).toBe('done');
});

it('a_replayed_plan_does_not_send_a_finished_step_back_to_pending', () => {
  const done = run({ steps: [step({ status: 'done', startedAt: STARTED, finishedAt: FINISHED })] });

  // The plan frame is a snapshot of the run's first second; replayed, it claims nothing has
  // started yet.
  const after = applyEvent(done, { type: 'run.planned', steps: [step()] }, LATER);

  expect(after.steps[0]?.status).toBe('done');
  expect(after.steps[0]?.startedAt).toBe(STARTED);
  expect(after.steps[0]?.finishedAt).toBe(FINISHED);
});

it('a_replayed_start_does_not_reopen_a_run_that_has_finished', () => {
  const done = play(run(), story(), STARTED);

  const after = applyEvent(done, { type: 'step.started', stepId: 'step-1' }, LATER);

  expect(after.status).toBe('done');
  expect(after.steps[0]?.status).toBe('done');
});

it('a_resync_does_not_overwrite_an_event_that_arrived_while_it_was_in_flight', () => {
  // The refetch left the server while the step was still running…
  const inFlight = run({ steps: [step({ status: 'running', startedAt: STARTED })] });
  // …the socket said it finished before the answer came back…
  const local = applyEvent(inFlight, {
    type: 'step.finished',
    stepId: 'step-1',
    status: 'done',
    result: { ok: true },
    tokens: 120,
    costUsd: 0.002,
    step: step({ status: 'done', startedAt: STARTED, finishedAt: FINISHED }),
  }, STARTED);
  // …and the answer describes the older world.
  const stale = run({ steps: [step({ status: 'running', startedAt: STARTED })] });

  const merged = mergeRun(local, stale);

  expect(merged.steps[0]?.status).toBe('done');
  expect(merged.steps[0]?.finishedAt).toBe(FINISHED);
});

it('a_resync_keeps_the_messages_the_socket_delivered_meanwhile', () => {
  const local = applyEvent(run(), {
    type: 'agent.message',
    id: 'm-2',
    fromAgent: 'coordinator',
    toAgent: 'user',
    content: 'Adım 1 sende',
    createdAt: STARTED,
  }, STARTED);
  const stale = run({
    messages: [
      { id: 'm-1', stepId: null, fromAgent: 'user', toAgent: 'coordinator', content: 'başla', createdAt: STARTED },
    ],
  });

  const merged = mergeRun(local, stale);

  expect(merged.messages.map((m) => m.id)).toEqual(['m-1', 'm-2']);
});

/**
 * The skipped status (the empty-precondition outcome, live 2026-08-01 17:36) travels the
 * same wire as every other terminal state. Its reason is on the server's copy of the step;
 * losing it in the reducer would draw "Atlandı" with nothing after the colon — and a skip
 * without its reason is unreviewable, which is the one thing a skip must never be.
 */
it('a_skipped_step_keeps_its_reason_through_the_finished_frame_and_a_replay', () => {
  const reason = 'Bugünkü maillerde iş talebi ya da hata bildirimi bulunamadı.';
  const finished: RunEvent = {
    type: 'step.finished',
    stepId: 'step-1',
    status: 'skipped',
    result: { skipped: true, reason },
    tokens: 12,
    costUsd: 0.0001,
    step: step({
      status: 'skipped',
      skipReason: reason,
      startedAt: STARTED,
      finishedAt: FINISHED,
    }),
  };

  const once = applyEvent(run(), finished, LATER);
  const twice = applyEvent(once, finished, LATER);

  expect(once.steps[0]?.status).toBe('skipped');
  expect(once.steps[0]?.skipReason).toBe(reason);
  expect(twice.steps[0]?.skipReason).toBe(reason);
});
