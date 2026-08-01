// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { RunSummary } from '../types/api';

/**
 * Why this file exists.
 *
 * <p>Geçmiş is a list of records with one exception in it: a run parked at the
 * approval gate is not a record, it is a question waiting on a person. The
 * screen used to draw it exactly like the finished ones — same card, same
 * height, same weight — and it disappeared into the list (#68).
 *
 * <p>The grouping that fixes that is one `filter` call away from being undone
 * by a refactor, and nothing else in the product would notice. These tests
 * notice: the waiting block exists, it is the first list on the page, and the
 * screen still says something out loud when there is nothing to show at all.
 */

const listRuns = vi.fn<(options?: { status?: string; size?: number }) => Promise<RunSummary[]>>();

vi.mock('../data', () => ({
  getRunSource: () => ({ listRuns }),
}));

const { HistoryScreen, repeatedGoals, splitByDecision } = await import('./HistoryScreen');

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

it('the_block_that_needs_a_decision_is_read_before_the_records', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'a', goal: 'biten iş', status: 'done' }),
    run({ id: 'b', goal: 'onay bekleyen iş', status: 'awaiting_approval' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);

  const waitingHead = await screen.findByText('Onayını bekleyen 1 çalıştırma');
  const lists = screen.getAllByRole('list');
  // First list on the page, and the waiting run is the only thing in it.
  expect(within(lists[0]!).getAllByRole('listitem')).toHaveLength(1);
  expect(lists[0]!.textContent).toContain('onay bekleyen iş');
  expect(waitingHead.closest('section')?.contains(lists[0]!)).toBe(true);
});

it('an_empty_history_says_so_instead_of_showing_an_empty_frame', async () => {
  listRuns.mockResolvedValue([]);

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('Henüz çalışmış akış yok')).toBeTruthy();
  expect(screen.queryByRole('list')).toBeNull();
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
 * The top bar counts every run stopped on a person; this screen used to show whichever of
 * them fell on the first page of history — 29 counted, 3 shown, and no route to the other
 * 26 (#100). The waiting block asks for the status as a set now, and it has to keep saying
 * only what the rows are: a server that ignores the filter must not turn finished runs into
 * pending decisions.
 */
it('the_waiting_block_holds_every_parked_run_not_just_the_first_page', async () => {
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

  expect(await screen.findByText('Onayını bekleyen 3 çalıştırma')).toBeTruthy();
  expect(screen.getByText('üçüncü onay')).toBeTruthy();
  // On the page but not waiting: it belongs under the records, once.
  expect(screen.getAllByText('biten iş')).toHaveLength(1);
});

it('a_server_that_ignores_the_filter_does_not_turn_records_into_decisions', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'bekleyen', status: 'awaiting_approval' }),
    run({ id: 'd-1', goal: 'biten iş', status: 'done' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('Onayını bekleyen 1 çalıştırma')).toBeTruthy();
});
