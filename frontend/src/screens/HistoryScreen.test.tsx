// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { RunSummary } from '../types/api';

/**
 * Why this file exists.
 *
 * <p>Akışlar carries two lists that answer different questions: a log of what ran, and
 * the runs parked at an approval gate. They used to be two sections stacked in one
 * column, and on the live box the queue was 28 rows deep — about a thousand pixels of
 * one list standing in front of the other (#133). Whoever came for either had to scroll
 * the other first.
 *
 * <p>Tabs fix that, and a tab is one `&&` away from being a section again. These tests
 * notice: only the chosen list is in the document, the log is what you land on, the
 * address names the tab so it can be linked and so Back works, and the queue still holds
 * every parked run rather than the ones that happened to fall on the page.
 */

const listRuns = vi.fn<(options?: { status?: string; size?: number }) => Promise<RunSummary[]>>();

vi.mock('../data', () => ({
  getRunSource: () => ({ listRuns }),
}));

const { HistoryScreen, hashForTab, repeatedGoals, splitByDecision, tabFromHash } = await import(
  './HistoryScreen'
);

function run(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: '11111111-2222-3333-4444-555555555555',
    goal: 'KAN projesindeki açık kayıtları listele',
    status: 'done',
    costTokens: 1200,
    costUsd: 0.004,
    budgetUsd: null,
    createdAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    stepCount: 3,
    ...overrides,
  };
}

beforeEach(() => {
  window.location.hash = '#/history';
});

afterEach(() => {
  cleanup();
  listRuns.mockReset();
});

it('a_run_waiting_for_approval_is_not_left_among_the_finished_ones', () => {
  const rows = [
    run({ id: 'a', status: 'done' }),
    run({ id: 'b', status: 'awaiting_approval' }),
    run({ id: 'c', status: 'failed' }),
    run({ id: 'd', status: 'awaiting_approval' }),
  ];

  const { waiting, settled } = splitByDecision(rows);

  expect(waiting.map((r) => r.id)).toEqual(['b', 'd']);
  expect(settled.map((r) => r.id)).toEqual(['a', 'c']);
});

it('two_runs_of_the_same_prompt_are_marked_so_they_can_be_told_apart', () => {
  const rows = [
    run({ id: 'a', goal: 'aynı istem' }),
    run({ id: 'b', goal: 'aynı istem' }),
    run({ id: 'c', goal: 'başka istem' }),
  ];

  expect(repeatedGoals(rows)).toEqual(new Set(['aynı istem']));
});

/**
 * The tab is a query parameter and not a path segment because `parseHash` reads the
 * segment after `history` as a run id — `#/history/bekleyen` would open a run detail
 * screen for a run that does not exist.
 */
it('an_unknown_tab_in_the_address_falls_back_to_the_log', () => {
  expect(tabFromHash('#/history')).toBe('tumu');
  expect(tabFromHash('#/history?durum=bekleyen')).toBe('bekleyen');
  expect(tabFromHash('#/history?durum=uydurma')).toBe('tumu');
  expect(hashForTab('bekleyen')).toBe('#/history?durum=bekleyen');
  expect(hashForTab('tumu')).toBe('#/history');
});

/**
 * The complaint that opened #133, in one assertion: arriving at this screen must not
 * mean arriving at the decision queue. The queue keeps its count in plain sight on its
 * own tab — what it no longer does is stand in front of the log.
 */
it('the_screen_opens_on_the_log_and_not_on_the_queue', async () => {
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval'
      ? [run({ id: 'w-1', goal: 'onay bekleyen iş', status: 'awaiting_approval' })]
      : [run({ id: 'd-1', goal: 'biten iş', status: 'done' })],
  );

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('biten iş')).toBeTruthy();
  const tabs = screen.getAllByRole('tab');
  expect(tabs[0]!.getAttribute('aria-selected')).toBe('true');
  // The count is on the tab; the rows behind it are not built.
  expect(within(tabs[1]!).getByText('1')).toBeTruthy();
  expect(screen.queryByText('onay bekleyen iş')).toBeNull();
});

it('choosing_a_tab_writes_it_to_the_address_so_the_list_can_be_linked_to', async () => {
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval'
      ? [run({ id: 'w-1', goal: 'onay bekleyen iş', status: 'awaiting_approval' })]
      : [run({ id: 'd-1', goal: 'biten iş', status: 'done' })],
  );

  render(<HistoryScreen onOpen={() => {}} />);
  (await screen.findByRole('tab', { name: /Onay bekleyen/ })).click();

  await waitFor(() => expect(screen.getByText('onay bekleyen iş')).toBeTruthy());
  expect(window.location.hash).toBe('#/history?durum=bekleyen');
  expect(screen.queryByText('biten iş')).toBeNull();
});

it('an_address_that_names_the_queue_opens_on_the_queue', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval'
      ? [run({ id: 'w-1', goal: 'onay bekleyen iş', status: 'awaiting_approval' })]
      : [run({ id: 'd-1', goal: 'biten iş', status: 'done' })],
  );

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('onay bekleyen iş')).toBeTruthy();
  expect(screen.queryByText('biten iş')).toBeNull();
});

it('an_empty_history_says_so_instead_of_showing_an_empty_frame', async () => {
  listRuns.mockResolvedValue([]);

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('Henüz çalışmış akış yok')).toBeTruthy();
  expect(screen.queryByRole('list')).toBeNull();
  expect(screen.queryByRole('tab')).toBeNull();
});

it('a_failure_to_read_the_history_is_reported_in_turkish_with_a_way_back', async () => {
  listRuns.mockRejectedValue(new TypeError('Failed to fetch'));

  render(<HistoryScreen onOpen={() => {}} />);

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toContain('Sunucuya ulaşılamadı');
  expect(alert.textContent).not.toMatch(/failed to fetch/i);

  listRuns.mockResolvedValue([run({ goal: 'ikinci denemede geldi' })]);
  screen.getByRole('button', { name: 'Tekrar dene' }).click();

  await waitFor(() => expect(screen.getByText('ikinci denemede geldi')).toBeTruthy());
});

/**
 * The waiting badge counts every run stopped on a person; this screen used to show
 * whichever of them fell on the first page of history — 29 counted, 3 shown, and no
 * route to the other 26 (#100). The queue asks for the status as a set, and it has to
 * keep saying only what the rows are: a server that ignores the filter must not turn
 * finished runs into pending decisions.
 */
it('the_queue_holds_every_parked_run_not_just_the_first_page', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval'
      ? [
          run({ id: 'w-1', goal: 'birinci onay', status: 'awaiting_approval' }),
          run({ id: 'w-2', goal: 'ikinci onay', status: 'awaiting_approval' }),
          run({ id: 'w-3', goal: 'üçüncü onay', status: 'awaiting_approval' }),
        ]
      : [
          run({ id: 'w-1', goal: 'birinci onay', status: 'awaiting_approval' }),
          run({ id: 'd-1', goal: 'biten iş', status: 'done' }),
        ],
  );

  render(<HistoryScreen onOpen={() => {}} />);

  const rows = await screen.findAllByRole('listitem');
  expect(rows).toHaveLength(3);
  expect(screen.getByText('üçüncü onay')).toBeTruthy();
});

it('a_server_that_ignores_the_filter_does_not_turn_records_into_decisions', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'bekleyen', status: 'awaiting_approval' }),
    run({ id: 'd-1', goal: 'biten iş', status: 'done' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);

  const rows = await screen.findAllByRole('listitem');
  expect(rows).toHaveLength(1);
  expect(rows[0]!.textContent).toContain('bekleyen');
});

/**
 * The queue is fetched separately and is usually longer than the page of history, so
 * counting repeats over the page alone hid the id on exactly the rows that needed it:
 * live, fifteen parkings of one prompt drew fifteen rows with the same goal, the same
 * age and the same token count.
 */
it('a_prompt_parked_twice_is_told_apart_even_when_the_page_holds_it_once', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval'
      ? [
          run({ id: 'aaaaaaaa-1111', goal: 'aynı istem', status: 'awaiting_approval' }),
          run({ id: 'bbbbbbbb-2222', goal: 'aynı istem', status: 'awaiting_approval' }),
        ]
      : [run({ id: 'aaaaaaaa-1111', goal: 'aynı istem', status: 'awaiting_approval' })],
  );

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('#aaaaaa')).toBeTruthy();
  expect(screen.getByText('#bbbbbb')).toBeTruthy();
});
