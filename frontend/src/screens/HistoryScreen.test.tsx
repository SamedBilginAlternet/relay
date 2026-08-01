// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
 *
 * <p>The table under the tabs is ag-grid now, which changed every one of these
 * assertions and none of the claims behind them. Where the old tests counted
 * `role="listitem"`, these count the grid's data rows; where they read a row's text,
 * these read the same row's text. The two claims that were about the *markup* rather
 * than about the product — that the frame is a `ul` of `li`, and that an empty history
 * has no `role="list"` in it — are the only ones that could not survive, and they are
 * re-stated below against what replaced them.
 */

/*
  ag-grid measures itself with a ResizeObserver, which jsdom does not implement. Without
  this the grid throws on mount and every test below fails for a reason that has nothing
  to do with what it is testing. A no-op is honest here: nothing in these tests depends
  on the grid reacting to a resize, and jsdom never resizes anything.
*/
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver ??= NoopResizeObserver;

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

/**
 * The runs the table is actually drawing.
 *
 * <p>An ag-grid row is a `div` with `role="row"`, and so is the header and so is the
 * filter row. `row-index` is the grid's own marker for a row that stands for a record,
 * which is the thing these tests want to count — the header is furniture, and counting
 * it was how "one parked run" would read as two.
 */
function runRows(): HTMLElement[] {
  const grid = screen.queryByRole('grid');
  if (!grid) return [];
  return within(grid)
    .queryAllByRole('row')
    .filter((row) => row.getAttribute('row-index') !== null);
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

/**
 * NEVER-BLANK. The old form of this test asserted there was no `role="list"` on the
 * screen; the frame is a grid now, so it asserts there is no grid. The claim is the one
 * it always was — an empty history is a sentence, not an empty table with column heads
 * and a filter row over nothing.
 */
it('an_empty_history_says_so_instead_of_showing_an_empty_frame', async () => {
  listRuns.mockResolvedValue([]);

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('Henüz çalışmış akış yok')).toBeTruthy();
  expect(screen.queryByRole('grid')).toBeNull();
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

  await screen.findByText('üçüncü onay');
  expect(runRows()).toHaveLength(3);
});

it('a_server_that_ignores_the_filter_does_not_turn_records_into_decisions', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'bekleyen', status: 'awaiting_approval' }),
    run({ id: 'd-1', goal: 'biten iş', status: 'done' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);

  await screen.findByText('bekleyen');
  const rows = runRows();
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

/**
 * The claim the old `role="listitem"` markup carried for free and ag-grid does not: a
 * run is something a keyboard can press and a screen reader can name.
 *
 * <p>An ag-grid row is a `div`. Left alone it is unreachable without the mouse and,
 * read out, it is six columns of bare figures — `3`, `1.200`, `$0.004000` — none of
 * which says what it is. The goal cell holds a real button whose label spells out all
 * five facts with their units, including the two the narrow layout does not print.
 */
it('every_row_is_a_control_a_keyboard_can_reach_and_a_reader_can_name', async () => {
  const opened: string[] = [];
  listRuns.mockResolvedValue([
    run({ id: 'run-42', goal: 'KAN kayıtlarını listele', status: 'done', stepCount: 4 }),
  ]);

  render(<HistoryScreen onOpen={(id) => opened.push(id)} />);

  const control = await screen.findByRole('button', { name: /KAN kayıtlarını listele/ });
  const label = control.getAttribute('aria-label') ?? '';
  expect(label).toContain('Tamamlandı');
  expect(label).toContain('4 adım');
  expect(label).toContain('1.200 token');
  expect(label).toContain('$0.004000');

  control.click();
  expect(opened).toEqual(['run-42']);
});

/**
 * The reason for the migration, and therefore the thing that has to be true.
 *
 * <p>The goal filter is the one this screen was missing: a hundred runs of a handful of
 * recurring prompts is what the live box looks like, and "the KAN one" is how people
 * ask for a run. It also has to own up to itself — a table that quietly drops half its
 * rows because of a box two scrolls up is how a reader concludes the data is gone.
 */
it('typing_in_the_goal_filter_narrows_the_table_and_the_table_says_so', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'a', goal: 'KAN kayıtlarını listele' }),
    run({ id: 'b', goal: 'fatura raporu çıkar' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('KAN kayıtlarını listele');

  const box = screen.getByPlaceholderText('İşin adında ara');
  // ag-grid's own input is not a React input: it listens for `input`, and a
  // `change` event alone leaves the filter untouched and the test green for
  // the wrong reason.
  fireEvent.input(box, { target: { value: 'fatura' } });

  await waitFor(() => expect(runRows()).toHaveLength(1));
  expect(screen.getByText('fatura raporu çıkar')).toBeTruthy();
  expect(screen.queryByText('KAN kayıtlarını listele')).toBeNull();
  expect(screen.getByText(/2 kayıttan 1 tanesi gösteriliyor/)).toBeTruthy();
});

/**
 * The status filter offers the words the product uses, over the statuses the table
 * actually holds.
 *
 * <p>Both halves are bugs waiting to happen. The column stores `failed` and prints
 * `Hata`, so a text filter here would match nothing the reader can see; and a list built
 * from the six statuses that exist rather than the two that are present offers choices
 * whose only outcome is an empty table.
 */
it('the_status_filter_offers_the_statuses_present_in_the_words_the_rows_use', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'a', goal: 'biten iş', status: 'done' }),
    run({ id: 'b', goal: 'patlayan iş', status: 'failed' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('biten iş');

  const select = screen.getByRole('combobox', { name: 'Duruma göre filtrele' });
  expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
    'Tüm durumlar',
    'Tamamlandı',
    'Hata',
  ]);
  // Never `İptal edildi`: nothing here was cancelled.
  expect(within(select).queryByText('İptal edildi')).toBeNull();

  fireEvent.change(select, { target: { value: 'failed' } });

  await waitFor(() => expect(runRows()).toHaveLength(1));
  expect(screen.getByText('patlayan iş')).toBeTruthy();
  expect(screen.queryByText('biten iş')).toBeNull();
});

/**
 * The two figures on the screen that a reader can compare are the tab's count and the
 * table's own note. A column filter moves one of them and must not move the other: the
 * queue holds what it holds whatever is typed into a search box.
 */
it('a_column_filter_does_not_change_what_the_tab_says_is_waiting', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'birinci onay', status: 'awaiting_approval' }),
    run({ id: 'w-2', goal: 'ikinci onay', status: 'awaiting_approval' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('birinci onay');

  fireEvent.input(screen.getByPlaceholderText('İşin adında ara'), {
    target: { value: 'ikinci' },
  });

  await waitFor(() => expect(runRows()).toHaveLength(1));
  const queueTab = screen.getByRole('tab', { name: /Onay bekleyen/ });
  expect(within(queueTab).getByText('2')).toBeTruthy();
});
