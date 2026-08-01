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
 * <p>The list under the tabs has been a stack of cards (#68), a hand-rolled table
 * (#124/#127), an ag-grid (#156) and — by the owner's decision — cards again (#163).
 * Every claim below has survived all four; only the way it is asked has changed. Where
 * the ag-grid tests counted `row-index`, these count list items; where those read a
 * row's text, these read a card's. Nothing has been dropped to go green.
 *
 * <p>The new claims are about the pager, and they are all one claim: a pager is a set of
 * figures about a list, and every one of them has to be about the list the reader thinks
 * it is about. The screen walks the server's pages to the end for exactly that reason —
 * a `1–15 / 100` over a server holding 222 runs is a page size wearing the clothes of a
 * total, which is the bug the note line above the list was written to fix.
 */

const listRuns =
  vi.fn<(options?: { status?: string; size?: number; page?: number }) => Promise<RunSummary[]>>();

vi.mock('../data', () => ({
  getRunSource: () => ({ listRuns }),
}));

const { HistoryScreen, hashForTab, repeatedGoals, splitByDecision, tabFromHash, walkRuns } =
  await import('./HistoryScreen');
const { applyQuery, PAGE_SIZE } = await import('../components/RunCards');

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

/** `n` runs, newest first, each one distinguishable from the next. */
function manyRuns(n: number, prefix = 'r'): RunSummary[] {
  return Array.from({ length: n }, (_, i) =>
    run({
      id: `${prefix}-${i}`,
      goal: `${prefix} işi ${i}`,
      createdAt: new Date(Date.now() - i * 60_000).toISOString(),
    }),
  );
}

/**
 * The runs the list is actually drawing.
 *
 * <p>One card is one list item, and the only list inside the tab panel is the log. The
 * ag-grid form of this counted `row-index` because every part of that widget — header,
 * filter row, body — was a `div` with `role="row"` on it; the claim it was protecting is
 * the same one, that furniture must not be counted as a run.
 */
function runCards(): HTMLElement[] {
  const panel = screen.queryByRole('tabpanel');
  if (!panel) return [];
  return within(panel).queryAllByRole('listitem');
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
  // The count is on the tab; the cards behind it are not built.
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
 * NEVER-BLANK. This test has been restated once per rewrite and the claim has not moved:
 * an empty history is a sentence, not a frame with controls over nothing. It has asserted
 * "no `role=list`", then "no `role=grid`"; it asserts both the list and the controls now,
 * because with the cards on the canvas the search box is the last thing that could be
 * left hanging over an empty screen.
 */
it('an_empty_history_says_so_instead_of_showing_an_empty_frame', async () => {
  listRuns.mockResolvedValue([]);

  render(<HistoryScreen onOpen={() => {}} />);

  expect(await screen.findByText('Henüz çalışmış akış yok')).toBeTruthy();
  expect(screen.queryByRole('tab')).toBeNull();
  expect(screen.queryByRole('listitem')).toBeNull();
  expect(screen.queryByPlaceholderText('İşin adında ara')).toBeNull();
  expect(screen.queryByRole('navigation', { name: 'Sayfalama' })).toBeNull();
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
  expect(runCards()).toHaveLength(3);
});

it('a_server_that_ignores_the_filter_does_not_turn_records_into_decisions', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'bekleyen', status: 'awaiting_approval' }),
    run({ id: 'd-1', goal: 'biten iş', status: 'done' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);

  await screen.findByText('bekleyen');
  const cards = runCards();
  expect(cards).toHaveLength(1);
  expect(cards[0]!.textContent).toContain('bekleyen');
});

/**
 * The queue is fetched separately and is usually longer than the page of history, so
 * counting repeats over the page alone hid the id on exactly the rows that needed it:
 * live, fifteen parkings of one prompt drew fifteen cards with the same goal, the same
 * age and the same token count.
 *
 * <p>The id is bare. It carried a `#` until #163, where the owner read `#7fd92e` as an
 * issue number — which on a screen whose goals are full of `RUN-88` is the one thing a
 * run id must not look like.
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

  expect(await screen.findByText('aaaaaa')).toBeTruthy();
  expect(screen.getByText('bbbbbb')).toBeTruthy();
  expect(screen.queryByText('#aaaaaa')).toBeNull();
});

/**
 * A run is something a keyboard can press and a screen reader can name.
 *
 * <p>The card prints its figures with their units next to them, which the table's bare
 * `3` could not do — but the whole card is still one button carrying one name, because
 * five separate fragments read out in a row do not say what they are. `$0.004000` is in
 * it at the width the API reports: a price rounded on the way to a label is a receipt
 * that disagrees with the run it belongs to.
 */
it('every_card_is_a_control_a_keyboard_can_reach_and_a_reader_can_name', async () => {
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
 * The filter this screen was missing until #156: a hundred runs of a handful of
 * recurring prompts is what the live box looks like, and "the KAN one" is how people ask
 * for a run. It also has to own up to itself — a list that quietly drops half its cards
 * because of a box above it is how a reader concludes the data is gone.
 */
it('typing_in_the_goal_filter_narrows_the_list_and_the_list_says_so', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'a', goal: 'KAN kayıtlarını listele' }),
    run({ id: 'b', goal: 'fatura raporu çıkar' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('KAN kayıtlarını listele');

  fireEvent.change(screen.getByPlaceholderText('İşin adında ara'), {
    target: { value: 'fatura' },
  });

  await waitFor(() => expect(runCards()).toHaveLength(1));
  expect(screen.getByText('fatura raporu çıkar')).toBeTruthy();
  expect(screen.queryByText('KAN kayıtlarını listele')).toBeNull();
  expect(screen.getByText(/2 kayıttan 1 tanesi gösteriliyor/)).toBeTruthy();
});

/**
 * Turkish, not `toLowerCase()`.
 *
 * <p>`'IŞIK'.toLowerCase()` is `ışık` in no locale JavaScript reaches by default: `I`
 * lowercases to `i`, and `İ` lowercases to an `i` with a combining dot. Either one makes
 * the filter miss at the first word anybody would type on a screen whose goals are
 * Turkish sentences.
 */
it('the_goal_filter_folds_case_the_turkish_way', () => {
  const rows = [
    run({ id: 'a', goal: 'IŞIK raporunu çıkar' }),
    run({ id: 'b', goal: 'İzin taleplerini topla' }),
    run({ id: 'c', goal: 'fatura raporu' }),
  ];
  const query = { status: null, sort: 'yeni' as const };

  expect(applyQuery(rows, { ...query, goal: 'ışık' }).map((r) => r.id)).toEqual(['a']);
  expect(applyQuery(rows, { ...query, goal: 'izin' }).map((r) => r.id)).toEqual(['b']);
  expect(applyQuery(rows, { ...query, goal: 'IZIN' }).map((r) => r.id)).toEqual([]);
});

/**
 * The status filter offers the words the product uses, over the statuses the list
 * actually holds.
 *
 * <p>Both halves are bugs waiting to happen. A run stores `failed` and the card prints
 * `Hata`, so a text filter here would match nothing the reader can see; and a list built
 * from the six statuses that exist rather than the two that are present offers choices
 * whose only outcome is an empty screen.
 */
it('the_status_filter_offers_the_statuses_present_in_the_words_the_cards_use', async () => {
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

  await waitFor(() => expect(runCards()).toHaveLength(1));
  expect(screen.getByText('patlayan iş')).toBeTruthy();
  expect(screen.queryByText('biten iş')).toBeNull();
});

/**
 * What the sortable column heads used to do, in the control that replaced them. The
 * table sorted Zaman, Adım, Token and Tutar on a click; cards have no heads, and losing
 * the capability with the heads would have been a regression nobody asked for.
 */
it('the_sort_control_answers_the_questions_the_column_heads_used_to', async () => {
  listRuns.mockResolvedValue([
    run({ id: 'ucuz', goal: 'ucuz iş', costUsd: 0.001, createdAt: '2026-08-01T10:00:00Z' }),
    run({ id: 'pahali', goal: 'pahalı iş', costUsd: 4.2, createdAt: '2026-07-01T10:00:00Z' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('ucuz iş');

  // Newest first is what the note above the list promises, so it is what it opens on.
  expect(runCards()[0]!.textContent).toContain('ucuz iş');

  fireEvent.change(screen.getByRole('combobox', { name: 'Sıralama' }), {
    target: { value: 'pahali' },
  });

  await waitFor(() => expect(runCards()[0]!.textContent).toContain('pahalı iş'));
});

/**
 * The two figures on the screen a reader can compare are the tab's count and the list's
 * own note. A filter moves one of them and must not move the other: the queue holds what
 * it holds whatever is typed into a search box.
 */
it('a_filter_does_not_change_what_the_tab_says_is_waiting', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  listRuns.mockResolvedValue([
    run({ id: 'w-1', goal: 'birinci onay', status: 'awaiting_approval' }),
    run({ id: 'w-2', goal: 'ikinci onay', status: 'awaiting_approval' }),
  ]);

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('birinci onay');

  fireEvent.change(screen.getByPlaceholderText('İşin adında ara'), {
    target: { value: 'ikinci' },
  });

  await waitFor(() => expect(runCards()).toHaveLength(1));
  const queueTab = screen.getByRole('tab', { name: /Onay bekleyen/ });
  expect(within(queueTab).getByText('2')).toBeTruthy();
});

/* ------------------------------------------------------------------ */
/* The pager (#163)                                                    */
/* ------------------------------------------------------------------ */

/**
 * A pager's figures are claims about a list, and the list they are claims about has to
 * be the log. Live the server holds 222 runs and answers one request with 100 — so a
 * pager reading `1–15 / 100` would be the page size wearing the clothes of a total, the
 * exact bug the note line above the list was written to fix (#124). The screen walks the
 * server's pages until one comes back short, and the pager counts what the walk found.
 */
it('the_walk_reads_past_the_first_response_so_the_total_is_the_log', async () => {
  const pages = [manyRuns(100, 'ilk'), manyRuns(22, 'son')];
  const asked: number[] = [];
  listRuns.mockImplementation(async (options?: { status?: string; page?: number }) => {
    if (options?.status === 'awaiting_approval') return [];
    asked.push(options?.page ?? 0);
    return pages[options?.page ?? 0] ?? [];
  });

  render(<HistoryScreen onOpen={() => {}} />);

  await screen.findByText('122 çalıştırma, yeniden eskiye.');
  expect(asked).toEqual([0, 1]);
  // A short page ends the walk: a third request would be one the server already
  // answered by handing back fewer rows than were asked for.
  expect(asked).not.toContain(2);
  expect(screen.getByText('1–15 / 122')).toBeTruthy();
});

/**
 * The Turkish the pager is written in, and the arithmetic under it. `Sayfa 1 / 9` and
 * `1–15 / 122` are the only two sentences on it, and neither is a translation of a grid
 * vendor's English left in place because nobody looked at the bottom of the list.
 */
it('the_pager_is_turkish_and_says_where_in_the_log_the_reader_is', async () => {
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval' ? [] : manyRuns(40),
  );

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('r işi 0');

  const pager = screen.getByRole('navigation', { name: 'Sayfalama' });
  expect(within(pager).getByText('1–15 / 40')).toBeTruthy();
  expect(pager.textContent).toContain('Sayfa');
  expect(within(pager).getByRole('button', { name: 'Sonraki sayfa' })).toBeTruthy();
  expect(within(pager).getByRole('button', { name: 'Son sayfa' })).toBeTruthy();
  // On page one there is nothing behind you, and the control says so rather than
  // taking a press and doing nothing.
  expect(within(pager).getByRole('button', { name: 'İlk sayfa' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(within(pager).getByRole('button', { name: 'Önceki sayfa' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(runCards()).toHaveLength(PAGE_SIZE);

  fireEvent.click(within(pager).getByRole('button', { name: 'Sonraki sayfa' }));

  await waitFor(() => expect(screen.getByText('16–30 / 40')).toBeTruthy());
  expect(screen.getByText('r işi 15')).toBeTruthy();
  expect(screen.queryByText('r işi 0')).toBeNull();

  fireEvent.click(within(pager).getByRole('button', { name: 'Son sayfa' }));

  // The last page is as short as what is left of the list — never fifteen rows padded
  // out to look full.
  await waitFor(() => expect(screen.getByText('31–40 / 40')).toBeTruthy());
  expect(runCards()).toHaveLength(10);
});

/**
 * Paging is not filtering. The tab's count is the size of the decision queue, and
 * walking to page two of it does not park or unpark anything.
 */
it('turning_a_page_does_not_change_what_the_tab_says_is_waiting', async () => {
  window.location.hash = '#/history?durum=bekleyen';
  const parked = manyRuns(20, 'onay').map((r) => ({ ...r, status: 'awaiting_approval' as const }));
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval' ? parked : [],
  );

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('onay işi 0');

  const queueTab = screen.getByRole('tab', { name: /Onay bekleyen/ });
  expect(within(queueTab).getByText('20')).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: 'Sonraki sayfa' }));

  await waitFor(() => expect(screen.getByText('16–20 / 20')).toBeTruthy());
  expect(within(screen.getByRole('tab', { name: /Onay bekleyen/ })).getByText('20')).toBeTruthy();
  expect(screen.getByText(/Bu 20 akış durdu/)).toBeTruthy();
});

/**
 * A page is a position in a list, so it cannot outlive the list it is a position in.
 * Filtering on page four of a twenty-page log used to be four pages of nothing, and a
 * reader looking at an empty list concludes their runs are gone rather than that they
 * are on a page that no longer exists.
 */
it('narrowing_the_list_returns_to_its_first_page', async () => {
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval' ? [] : manyRuns(40),
  );

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('r işi 0');

  fireEvent.click(screen.getByRole('button', { name: 'Son sayfa' }));
  await waitFor(() => expect(screen.getByText('31–40 / 40')).toBeTruthy());

  fireEvent.change(screen.getByPlaceholderText('İşin adında ara'), { target: { value: 'işi 3' } });

  // `işi 3`, `işi 30`…`işi 39` — eleven runs, and the reader is looking at the first of
  // them rather than at the fourth page of a list that is now one page long.
  await waitFor(() => expect(screen.getByText('r işi 3')).toBeTruthy());
  expect(screen.queryByRole('navigation', { name: 'Sayfalama' })).toBeNull();
});

/**
 * No pager over a list that has one page. The controls on this screen are all things the
 * reader can act on; four arrows that cannot go anywhere are furniture, and this product
 * counts furniture as a cost.
 */
it('a_log_that_fits_on_one_page_carries_no_pager', async () => {
  listRuns.mockImplementation(async (options?: { status?: string }) =>
    options?.status === 'awaiting_approval' ? [] : manyRuns(PAGE_SIZE),
  );

  render(<HistoryScreen onOpen={() => {}} />);
  await screen.findByText('r işi 0');

  expect(runCards()).toHaveLength(PAGE_SIZE);
  expect(screen.queryByRole('navigation', { name: 'Sayfalama' })).toBeNull();
});

/**
 * The walk has a ceiling, and when it is reached the note says so in words. The pager
 * cannot: its total is the number of runs in hand, and on a box with more history than
 * the ceiling allows that number is honest about the list and silent about the log.
 */
it('a_walk_cut_short_by_its_ceiling_is_admitted_in_the_note', async () => {
  let served = 0;
  listRuns.mockImplementation(async (options?: { status?: string }) => {
    if (options?.status === 'awaiting_approval') return [];
    served += 1;
    return manyRuns(100, `sayfa${served}`);
  });

  render(<HistoryScreen onOpen={() => {}} />);

  await screen.findByText('En yeni 500 çalıştırma, yeniden eskiye — daha eskisi bu listede yok.');
  // Five requests, not five hundred: the ceiling is what stops one visit to a busy box
  // from becoming a denial of service on its own backend.
  expect(served).toBe(5);
});

/**
 * A server that does not understand `page` answers every request with the same first
 * hundred. Walking on would stack five copies of it and report 500 runs that do not
 * exist — a number the pager would print as a total in good faith.
 */
it('a_server_that_ignores_the_page_parameter_is_not_reported_five_times_over', async () => {
  const once = manyRuns(100);
  let calls = 0;
  const walked = await walkRuns(async () => {
    calls += 1;
    return once;
  });

  expect(walked.rows).toHaveLength(100);
  expect(walked.complete).toBe(true);
  expect(calls).toBe(2);
});

/** The ordinary end of the walk: a page shorter than the one asked for. */
it('the_walk_stops_at_the_first_short_page', async () => {
  const asked: number[] = [];
  const walked = await walkRuns(async ({ page }) => {
    asked.push(page);
    return page === 0 ? manyRuns(100, 'a') : manyRuns(7, 'b');
  });

  expect(walked.rows).toHaveLength(107);
  expect(walked.complete).toBe(true);
  expect(asked).toEqual([0, 1]);
});
