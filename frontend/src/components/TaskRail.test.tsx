// @vitest-environment jsdom
import { cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Run, RunSummary } from '../types/api';

/**
 * Why this file exists.
 *
 * <p>Sohbet showed one flow at a time. Measured on the live box on 2026-08-01,
 * `GET /api/runs?status=awaiting_approval` answered with 28 runs stopped on a human
 * decision while the screen could show one of them, and the default history page — the
 * obvious thing to build a rail out of — held only 3 of those 28 (#125, part of #124).
 *
 * <p>So three claims are worth a test forever. The rail asks the server per status instead
 * of filtering a page it happens to have. A run waiting on a person sorts above one that is
 * merely running, because a decision the user owes is the expensive thing to miss. And with
 * nothing alive there is no rail in the document at all — an empty rail is furniture, and it
 * would cost the composer a column of width to say nothing.
 */

const listRuns = vi.fn<(o?: { status?: string; size?: number }) => Promise<RunSummary[]>>();

vi.mock('../data', () => ({
  getRunSource: () => ({ listRuns }),
  RUN_SOURCE_KIND: 'api',
}));

const { TaskRail, useLiveRuns, orderLiveRuns, progressLabel } = await import('./TaskRail');
type RailRun = Parameters<typeof orderLiveRuns>[0][number];

function summary(over: Partial<RunSummary> & { id: string }): RunSummary {
  return {
    goal: 'Bugünkü maillerime bak',
    status: 'running',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    createdAt: '2026-08-01T09:00:00Z',
    finishedAt: null,
    stepCount: 3,
    ...over,
  };
}

function rail(over: Partial<RailRun> & { id: string }): RailRun {
  return {
    goal: 'Bugünkü maillerime bak',
    status: 'running',
    stepCount: 3,
    done: null,
    createdAt: '2026-08-01T09:00:00Z',
    ...over,
  };
}

function run(over: Partial<Run> & { id: string }): Run {
  return {
    goal: 'Açık kayıtları çıkar',
    status: 'running',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    steps: [],
    messages: [],
    createdAt: '2026-08-01T10:00:00Z',
    finishedAt: null,
    ...over,
  };
}

beforeEach(() => {
  listRuns.mockReset();
  listRuns.mockResolvedValue([]);
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
  }));
});

afterEach(cleanup);

it('a_run_waiting_on_a_person_sorts_above_one_that_started_later', () => {
  const ordered = orderLiveRuns([
    rail({ id: 'newest-running', status: 'running', createdAt: '2026-08-01T12:00:00Z' }),
    rail({ id: 'older-waiting', status: 'awaiting_approval', createdAt: '2026-08-01T08:00:00Z' }),
    rail({ id: 'newest-waiting', status: 'awaiting_approval', createdAt: '2026-08-01T11:00:00Z' }),
    rail({ id: 'older-planning', status: 'planning', createdAt: '2026-08-01T07:00:00Z' }),
  ]);

  expect(ordered.map((r) => r.id)).toEqual([
    'newest-waiting',
    'older-waiting',
    'newest-running',
    'older-planning',
  ]);
});

it('a_finished_run_never_reaches_the_rail', () => {
  const ordered = orderLiveRuns([
    rail({ id: 'done', status: 'done' }),
    rail({ id: 'failed', status: 'failed' }),
    rail({ id: 'cancelled', status: 'cancelled' }),
    rail({ id: 'alive', status: 'running' }),
  ]);

  expect(ordered.map((r) => r.id)).toEqual(['alive']);
});

it('a_run_answered_by_two_status_queries_takes_one_row_not_two', () => {
  // Three requests go out; a run that moves from running to the gate between two of them
  // comes back in both, and the newer answer is the one that must survive.
  const ordered = orderLiveRuns([
    rail({ id: 'r-1', status: 'running' }),
    rail({ id: 'r-1', status: 'awaiting_approval' }),
  ]);

  expect(ordered).toHaveLength(1);
  expect(ordered[0]?.status).toBe('awaiting_approval');
});

it('progress_states_the_total_alone_when_no_one_counted_the_finished_steps', () => {
  // The list endpoint sends `stepCount` and nothing about how many of them are done.
  expect(progressLabel(5, null)).toBe('5 adım');
  expect(progressLabel(5, 3)).toBe('3/5 adım');
  // A plan that does not exist yet has no progress to report — not "0/0".
  expect(progressLabel(0, 0)).toBeNull();
});

it('no_live_runs_means_no_rail_element_at_all', () => {
  const { container } = render(<TaskRail runs={[]} currentRunId={null} onOpen={() => {}} />);

  expect(container.querySelector('.rail')).toBeNull();
  expect(container.innerHTML).toBe('');
});

it('the_open_run_is_the_only_row_marked_as_open', () => {
  render(
    <TaskRail
      runs={[
        rail({ id: 'r-1', status: 'awaiting_approval', goal: 'Kararını bekleyen iş' }),
        rail({ id: 'r-2', status: 'running', goal: 'Süren iş' }),
      ]}
      currentRunId="r-2"
      onOpen={() => {}}
    />,
  );

  const marked = document.querySelectorAll('[aria-current="true"]');
  expect(marked).toHaveLength(1);
  expect(marked[0]?.textContent).toContain('Süren iş');
});

it('a_row_says_its_status_and_how_far_along_it_is', () => {
  render(
    <TaskRail
      runs={[rail({ id: 'r-1', status: 'awaiting_approval', stepCount: 5, done: 3 })]}
      currentRunId="r-1"
      onOpen={() => {}}
    />,
  );

  expect(screen.getByText('Onay bekliyor')).not.toBeNull();
  expect(screen.getByText('3/5 adım')).not.toBeNull();
  // Machine facts stay in the mono layer (DESIGN.md v3, rule 1).
  expect(screen.getByText('3/5 adım').className).toContain('t-mono');
});

it('clicking_a_row_asks_for_that_run_by_id', () => {
  const onOpen = vi.fn();
  render(
    <TaskRail
      runs={[rail({ id: 'r-1', goal: 'Süren iş' }), rail({ id: 'r-2', goal: 'Öteki iş' })]}
      currentRunId="r-1"
      onOpen={onOpen}
    />,
  );

  fireEvent.click(screen.getByTitle('Öteki iş'));

  expect(onOpen).toHaveBeenCalledWith('r-2');
});

it('the_rail_asks_the_server_per_status_instead_of_sifting_a_page_of_history', async () => {
  listRuns.mockResolvedValue([]);

  renderHook(() => useLiveRuns(null));

  await waitFor(() => expect(listRuns).toHaveBeenCalledTimes(3));
  const asked = listRuns.mock.calls.map(([options]) => options?.status);
  expect(asked.sort()).toEqual(['awaiting_approval', 'planning', 'running']);
  // And never the unfiltered page — that is the twenty rows this rail exists to stop
  // trusting.
  expect(listRuns.mock.calls.every(([options]) => options?.status != null)).toBe(true);
});

it('the_open_run_appears_even_before_any_list_has_heard_of_it', async () => {
  listRuns.mockResolvedValue([]);
  const fresh = run({ id: 'r-new', status: 'planning', goal: 'Yeni başlayan iş' });

  const { result } = renderHook(() => useLiveRuns(fresh));

  await waitFor(() => expect(listRuns).toHaveBeenCalled());
  expect(result.current.map((r) => r.id)).toEqual(['r-new']);
});

it('a_failed_refresh_leaves_the_rail_standing_rather_than_emptying_it', async () => {
  listRuns.mockResolvedValue([summary({ id: 'r-1', status: 'awaiting_approval' })]);
  const { result, rerender } = renderHook(({ current }) => useLiveRuns(current), {
    initialProps: { current: null as Run | null },
  });
  await waitFor(() => expect(result.current).toHaveLength(1));

  // A request that did not happen is not evidence that the queue emptied.
  listRuns.mockRejectedValue(new Error('offline'));
  rerender({ current: run({ id: 'r-9', status: 'done' }) });

  await waitFor(() => expect(listRuns.mock.calls.length).toBeGreaterThan(3));
  expect(result.current.map((r) => r.id)).toEqual(['r-1']);
});
