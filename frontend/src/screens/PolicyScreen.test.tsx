// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { PolicyMode, ToolPolicy } from '../data/PolicySource';

/**
 * Why this file exists.
 *
 * <p>Politikalar prints the default a tool would run under only when the tool
 * is no longer running under it (#67). Before that, every row carried the same
 * sentence — "varsayılan: otomatik (okuma riski)" under a row already showing
 * `okuma` and a lit `Otomatik` — eighteen times, which is how a rule table
 * turns into wallpaper nobody reads.
 *
 * <p>The condition is one `&&` away from coming back, and the row it hangs off
 * has now been rebuilt three times (#14, #127, #154) without a test underneath
 * it. These are that test. The third one is the case the API actually produces:
 * `PUT /api/policies` cannot delete a stored record, so a tool put back on its
 * default still returns `overridden: true` — the flag is not the question, the
 * effective mode is.
 *
 * <p>The rest are about the two axes the screen filters on (#154). The tabs
 * name the APP and the chips name the RULE, and three of the assertions here
 * are load-bearing for decisions that were argued over:
 *
 * <ul>
 *   <li>the tab list is DERIVED from the tool ids, so a provider we ship no
 *       tool for can never get a tab and Gmail and Takvim can never go back to
 *       sharing one called "Google";
 *   <li>the rule filter is not a second `role="tablist"` — two stacked strips
 *       leave the reader guessing which one is the list;
 *   <li>a row whose rule you change while a filter is on stays where it is.
 *       That is the trap the run list does not have: here the reader changes
 *       the very value the list is filtered by, so the row they just pressed
 *       would leave the screen under their cursor and a change that worked
 *       would look like one that failed.
 * </ul>
 */

let rows: ToolPolicy[] = [];
const setMode = vi.fn<(tool: string, mode: PolicyMode) => Promise<ToolPolicy[]>>();

vi.mock('../data/PolicySource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../data/PolicySource')>();
  return {
    ...actual,
    getPolicySource: () => ({ list: async () => rows, setMode }),
  };
});

const { PolicyScreen, hashForView, viewFromHash } = await import('./PolicyScreen');

function tool(overrides: Partial<ToolPolicy> = {}): ToolPolicy {
  return {
    provider: 'jira',
    toolName: 'jira.getIssue',
    risk: 'read',
    mode: 'auto',
    overridden: false,
    ...overrides,
  };
}

/** The row a tool is drawn in, found by the tool id printed in it. */
async function rowOf(toolName: string): Promise<HTMLElement> {
  return (await screen.findByText(toolName)).closest('li') as HTMLElement;
}

/** One of the rule chips, by the rule it filters to. */
function chip(label: string): HTMLElement {
  const found = screen
    .getAllByRole('button')
    .find((b) => b.className.includes('pol-chip') && b.textContent?.startsWith(label));
  if (!found) throw new Error(`no rule chip called ${label}`);
  return found;
}

beforeEach(() => {
  window.location.hash = '#/politikalar';
  rows = [];
  setMode.mockReset();
});

afterEach(cleanup);

it('a_tool_running_at_its_default_does_not_repeat_the_default_back', async () => {
  rows = [tool(), tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' })];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(screen.queryByText(/Operatör değiştirdi/)).toBeNull();
  expect(screen.queryByText(/^varsayılan:/)).toBeNull();
  expect(screen.queryByRole('button', { name: /Varsayılana dön/ })).toBeNull();
});

it('a_tool_moved_off_its_default_names_the_default_it_left', async () => {
  rows = [
    tool(),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'auto', overridden: true }),
  ];

  render(<PolicyScreen />);
  const moved = await rowOf('jira.createIssue');

  expect(within(moved).getByText(/Operatör değiştirdi/)).toBeTruthy();
  expect(within(moved).getByText('varsayılan: onay ister (yazma riski)')).toBeTruthy();
  expect(within(moved).getByRole('button', { name: /Varsayılana dön/ })).toBeTruthy();

  // The tool that never moved keeps its row silent.
  expect(within(await rowOf('jira.getIssue')).queryByText(/varsayılan:/)).toBeNull();
});

/**
 * The row the server actually sends back after "varsayılana dön": stored
 * record still there, mode back at the default. It is not a deviation.
 */
it('a_stored_record_at_the_default_mode_is_not_a_deviation', async () => {
  rows = [tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask', overridden: true })];

  render(<PolicyScreen />);
  await screen.findByText('jira.createIssue');

  expect(screen.queryByText(/Operatör değiştirdi/)).toBeNull();
  expect(screen.queryByText(/^varsayılan:/)).toBeNull();
});

it('putting_a_tool_back_on_its_default_takes_the_line_away_with_it', async () => {
  rows = [tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'auto', overridden: true })];
  setMode.mockResolvedValue([
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask', overridden: true }),
  ]);

  render(<PolicyScreen />);
  const moved = await rowOf('jira.createIssue');
  within(moved).getByRole('button', { name: /Varsayılana dön/ }).click();

  await waitFor(() => expect(screen.queryByText(/Operatör değiştirdi/)).toBeNull());
  expect(setMode).toHaveBeenCalledWith('jira.createIssue', 'ask');
});

/**
 * The banner that used to list the strayed tools above the table cost 56px the
 * moment anything deviated, on a screen whose whole complaint was that it
 * scrolled. The count survived the move into the table head; if it ever stops
 * being drawn, the only global "something is off" signal has gone with it.
 */
it('a_tool_off_its_default_is_counted_where_every_tab_can_see_it', async () => {
  rows = [
    tool(),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'auto', overridden: true }),
    tool({ provider: 'slack', toolName: 'slack.postMessage', risk: 'write', mode: 'auto' }),
    tool({ provider: 'google', toolName: 'gmail.search' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  const off = await screen.findByText('2 araç varsayılan dışı');
  expect(off.getAttribute('title')).toBe('jira.createIssue, slack.postMessage');

  // Still counted from a tab that draws neither of them.
  screen.getByRole('tab', { name: /Gmail/ }).click();
  await waitFor(() => expect(screen.queryByText('jira.createIssue')).toBeNull());
  expect(screen.queryByText('slack.postMessage')).toBeNull();
  expect(screen.getByText('2 araç varsayılan dışı')).toBeTruthy();
});

/* ---- the address ---------------------------------------------------- */

it('an_unreadable_filter_in_the_address_falls_back_to_the_whole_table', () => {
  expect(viewFromHash('#/politikalar')).toEqual({ provider: 'tumu', mode: null });
  expect(viewFromHash('#/politikalar?saglayici=jira')).toEqual({ provider: 'jira', mode: null });
  expect(viewFromHash('#/politikalar?kural=onay')).toEqual({ provider: 'tumu', mode: 'ask' });
  expect(viewFromHash('#/politikalar?saglayici=gmail&kural=yasak')).toEqual({
    provider: 'gmail',
    mode: 'forbidden',
  });
  expect(viewFromHash('#/politikalar?kural=uydurma')).toEqual({ provider: 'tumu', mode: null });

  expect(hashForView({ provider: 'tumu', mode: null })).toBe('#/politikalar');
  expect(hashForView({ provider: 'calendar', mode: null })).toBe('#/politikalar?saglayici=calendar');
  expect(hashForView({ provider: 'tumu', mode: 'forbidden' })).toBe('#/politikalar?kural=yasak');
  expect(hashForView({ provider: 'jira', mode: 'ask' })).toBe(
    '#/politikalar?saglayici=jira&kural=onay',
  );
});

/**
 * `?kural=` selected a TAB before the providers took the tabs. It selects a
 * chip now, and it has to keep meaning the same thing or every link anyone
 * has written to this screen opens something else.
 */
it('a_link_written_before_the_provider_tabs_still_opens_the_rule_it_named', async () => {
  window.location.hash = '#/politikalar?kural=onay';
  rows = [tool(), tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' })];

  render(<PolicyScreen />);

  expect(await screen.findByText('jira.createIssue')).toBeTruthy();
  expect(screen.queryByText('jira.getIssue')).toBeNull();
});

/* ---- the tabs name the app ------------------------------------------ */

/**
 * Derived from the tool ids, never from a list kept by hand. The server calls
 * Gmail and Calendar both `google` and they were sharing one band headed
 * "Google — Gmail + Takvim", which answers neither question anybody brings to
 * this screen. `providerOf` splits them; nothing else may put them back.
 */
it('every_provider_with_a_registered_tool_gets_a_tab_and_nothing_else_does', async () => {
  rows = [
    tool(),
    tool({ provider: 'google', toolName: 'gmail.search' }),
    tool({ provider: 'google', toolName: 'calendar.listToday' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
    'Tümü3',
    'Jira1',
    'Gmail1',
    'Takvim1',
  ]);
  // No tool is registered for these, so no tab claims them.
  expect(screen.queryByRole('tab', { name: /GitHub/ })).toBeNull();
  expect(screen.queryByRole('tab', { name: /Slack/ })).toBeNull();
  expect(screen.queryByRole('tab', { name: /Google/ })).toBeNull();
});

/** The mark is the whole point of the tab (#154) — and it is never borrowed. */
it('a_provider_tab_wears_its_own_mark_and_a_tab_for_no_provider_wears_none', async () => {
  rows = [tool(), tool({ provider: 'başka', toolName: 'başka.birşey' })];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(screen.getByRole('tab', { name: /Jira/ }).querySelector('svg')).toBeTruthy();
  // "Tümü" is not a provider and the unrecognised one has no mark we own.
  expect(screen.getByRole('tab', { name: /Tümü/ }).querySelector('svg')).toBeNull();
  expect(screen.getByRole('tab', { name: /başka/ }).querySelector('svg')).toBeNull();
});

/**
 * sheets.* used to be the hole in the split above: `providerOf` did not know the
 * namespace, so both Sheets rules fell back to the server's `google` and stood
 * under a tab with a raw id and no mark — while Gmail, Takvim and Doküman, on the
 * exact same connection, wore theirs. Same publisher, same brand permission, same
 * naming rule (the product's Turkish noun, like Takvim).
 */
it('a_sheets_rule_stands_under_its_own_named_tab_not_under_a_raw_google_one', async () => {
  rows = [
    tool({ provider: 'google', toolName: 'sheets.appendRow', risk: 'write', mode: 'ask' }),
    tool({ provider: 'google', toolName: 'gmail.search' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('sheets.appendRow');

  const tab = screen.getByRole('tab', { name: /Tablo/ });
  expect(tab.querySelector('svg')).toBeTruthy();
  expect(screen.queryByRole('tab', { name: /google/i })).toBeNull();
});

it('the_all_tab_draws_every_rule_and_a_provider_tab_draws_only_its_own', async () => {
  rows = [
    tool(),
    tool({ provider: 'google', toolName: 'gmail.search' }),
    tool({ provider: 'slack', toolName: 'slack.listChannels' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');
  expect(screen.getByText('gmail.search')).toBeTruthy();
  expect(screen.getByText('slack.listChannels')).toBeTruthy();

  screen.getByRole('tab', { name: /Gmail/ }).click();

  await waitFor(() => expect(screen.queryByText('jira.getIssue')).toBeNull());
  expect(screen.getByText('gmail.search')).toBeTruthy();
  expect(screen.queryByText('slack.listChannels')).toBeNull();
  expect(window.location.hash).toBe('#/politikalar?saglayici=gmail');
});

/* ---- the chips name the rule ---------------------------------------- */

/**
 * The strip this started as printed 12 / 6 / 0 and could not be pressed; then
 * it was the tab counts. The number has to survive each move, or the screen
 * lost a fact to gain a control.
 */
it('each_rule_chip_carries_the_size_of_the_group_behind_it', async () => {
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.searchIssues' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(within(chip('Otomatik')).getByText('2')).toBeTruthy();
  expect(within(chip('Onay ister')).getByText('1')).toBeTruthy();
  // Nothing is forbidden, so the chip carries no number rather than a zero.
  expect(within(chip('Yasak')).queryByText('0')).toBeNull();
});

/** The chip counts what the tab holds, not what the whole table holds. */
it('the_rule_counts_are_for_the_tab_you_are_standing_in', async () => {
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.searchIssues' }),
    tool({ provider: 'slack', toolName: 'slack.listChannels' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');
  expect(within(chip('Otomatik')).getByText('3')).toBeTruthy();

  screen.getByRole('tab', { name: /Slack/ }).click();

  await waitFor(() => expect(screen.queryByText('jira.getIssue')).toBeNull());
  expect(within(chip('Otomatik')).getByText('1')).toBeTruthy();
});

/**
 * One strip of tabs, not two. The rule filter works INSIDE the selected tab and
 * a second `role="tablist"` stacked on the first would make the reader work out
 * which row is the list and which is the filter — the thing #154 was opened to
 * stop happening.
 */
it('the_rule_filter_is_not_a_second_tab_strip', async () => {
  rows = [tool()];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(screen.getAllByRole('tablist')).toHaveLength(1);
  expect(screen.getAllByRole('tab').every((t) => !t.className.includes('pol-chip'))).toBe(true);
  expect(chip('Otomatik').getAttribute('aria-pressed')).toBe('false');
});

it('a_pressed_rule_chip_draws_only_the_tools_running_under_it', async () => {
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');
  chip('Onay ister').click();

  await waitFor(() => expect(screen.queryByText('jira.getIssue')).toBeNull());
  expect(screen.getByText('jira.createIssue')).toBeTruthy();
  expect(window.location.hash).toBe('#/politikalar?kural=onay');
  expect(chip('Onay ister').getAttribute('aria-pressed')).toBe('true');
});

/** A filter you cannot let go of is a trap. Pressing the pressed chip clears it. */
it('pressing_the_pressed_rule_chip_again_lets_the_filter_go', async () => {
  window.location.hash = '#/politikalar?kural=onay';
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.createIssue');
  expect(screen.queryByText('jira.getIssue')).toBeNull();

  chip('Onay ister').click();

  expect(await screen.findByText('jira.getIssue')).toBeTruthy();
  expect(window.location.hash).toBe('#/politikalar');
});

/** Both axes at once, and the address says so. */
it('a_provider_and_a_rule_narrow_the_table_together', async () => {
  window.location.hash = '#/politikalar?saglayici=jira&kural=onay';
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
    tool({ provider: 'google', toolName: 'gmail.createDraft', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);

  expect(await screen.findByText('jira.createIssue')).toBeTruthy();
  expect(screen.queryByText('jira.getIssue')).toBeNull();
  expect(screen.queryByText('gmail.createDraft')).toBeNull();
});

/* ---- the traps ------------------------------------------------------ */

/**
 * The trap the run list does not have. Filtered to `Otomatik`, moving a tool to
 * `Onay ister` matches the filter no longer — and a row that disappears the
 * instant you press it reads as a failure, not as a change that took.
 */
it('a_tool_moved_out_of_the_filtered_rule_keeps_its_place_and_says_where_it_went', async () => {
  window.location.hash = '#/politikalar?kural=otomatik';
  rows = [tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'auto', overridden: true })];
  setMode.mockResolvedValue([
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask', overridden: true }),
  ]);

  render(<PolicyScreen />);
  const row = await rowOf('jira.createIssue');
  within(row).getByRole('radio', { name: /Onay ister/ }).click();

  await waitFor(() => expect(setMode).toHaveBeenCalledWith('jira.createIssue', 'ask'));
  const stayed = await rowOf('jira.createIssue');
  expect(within(stayed).getByText('→ Onay ister')).toBeTruthy();
});

it('an_empty_rule_says_what_it_is_empty_of_instead_of_drawing_nothing', async () => {
  window.location.hash = '#/politikalar?kural=yasak';
  rows = [tool({ toolName: 'jira.getIssue' })];

  render(<PolicyScreen />);

  expect(await screen.findByText('Yasak kuralında araç yok')).toBeTruthy();
  expect(screen.queryByText('jira.getIssue')).toBeNull();
});

/** With both axes on, the empty frame has to say which two things it is empty of. */
it('an_empty_tab_and_rule_together_name_both_halves_of_the_question', async () => {
  window.location.hash = '#/politikalar?saglayici=jira&kural=yasak';
  rows = [tool({ toolName: 'jira.getIssue' })];

  render(<PolicyScreen />);

  expect(await screen.findByText('Jira altında Yasak kuralında araç yok')).toBeTruthy();
});

/**
 * The rule about the tools that are NOT in the table (#14). It is permanently
 * forbidden, so it must never be drawn under a filter that says "these run
 * unattended" — and it belongs to no provider, so it is not drawn inside one.
 */
it('the_rule_for_unregistered_tools_is_never_drawn_under_a_rule_that_runs_things', async () => {
  rows = [tool({ toolName: 'jira.getIssue' })];

  render(<PolicyScreen />);
  expect(await screen.findByText('bilinmeyen araç adı')).toBeTruthy();

  chip('Otomatik').click();
  await waitFor(() => expect(screen.queryByText('bilinmeyen araç adı')).toBeNull());

  chip('Otomatik').click();
  expect(await screen.findByText('bilinmeyen araç adı')).toBeTruthy();

  screen.getByRole('tab', { name: /Jira/ }).click();
  await waitFor(() => expect(screen.queryByText('bilinmeyen araç adı')).toBeNull());
});
