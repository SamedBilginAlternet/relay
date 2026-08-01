import { beforeEach, expect, it, vi } from 'vitest';
import type { RunStreamHandlers } from '../data/RunSource';
import type { Run } from '../types/api';

/**
 * A finished flow lets go of its connection.
 *
 * <p>Neither side used to hang up. The server kept the stream for its full thirty-minute
 * timeout; the browser read that timeout as a dropped line and reconnected; the backend
 * replayed all four hundred events onto a run that had been over for half an hour, and every
 * replayed frame stamped the step timings with the clock of the moment. One blip in the
 * demo network wiped the durations off the screen — and then did it again half an hour later.
 */

let handlers: RunStreamHandlers | null = null;
const unsubscribe = vi.fn();

function runFixture(status: string): Run {
  return {
    id: 'run-1',
    goal: 'Jira blocker',
    status,
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    steps: [],
    messages: [],
    createdAt: '2026-07-31T09:00:00.000Z',
    finishedAt: null,
  };
}

let served: Run = runFixture('running');

vi.mock('../data', () => ({
  RUN_SOURCE_KIND: 'mock',
  getRunSource: () => ({
    getRun: () => Promise.resolve(served),
    streamRun: (_runId: string, given: RunStreamHandlers) => {
      handlers = given;
      given.onStatus('live');
      return unsubscribe;
    },
  }),
}));

const { useRunStore } = await import('./runStore');

beforeEach(() => {
  handlers = null;
  unsubscribe.mockClear();
  served = runFixture('running');
  useRunStore.setState({ run: null, phase: 'idle', streamStatus: 'idle' });
});

it('a_finished_run_stops_listening', async () => {
  await useRunStore.getState().openRun('run-1');
  expect(handlers).not.toBeNull();
  expect(useRunStore.getState().streamStatus).toBe('live');

  handlers?.onEvent({ type: 'run.finished', status: 'done' });

  expect(unsubscribe).toHaveBeenCalledTimes(1);
  expect(useRunStore.getState().streamStatus).toBe('closed');
  expect(useRunStore.getState().run?.status).toBe('done');
});

it('leaving_the_screen_lets_go_of_the_connection_but_keeps_the_run', async () => {
  await useRunStore.getState().openRun('run-1');

  useRunStore.getState().stopWatching();

  expect(unsubscribe).toHaveBeenCalledTimes(1);
  expect(useRunStore.getState().run?.id).toBe('run-1');
});

it('coming_back_to_a_run_that_is_still_going_starts_listening_again', async () => {
  await useRunStore.getState().openRun('run-1');
  useRunStore.getState().stopWatching();
  handlers = null;

  useRunStore.getState().watchRun();

  expect(handlers).not.toBeNull();
});

it('coming_back_to_a_run_that_already_ended_does_not_reopen_the_stream', async () => {
  served = runFixture('done');
  await useRunStore.getState().openRun('run-1');
  expect(handlers).toBeNull();

  useRunStore.getState().watchRun();

  expect(handlers).toBeNull();
});
