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
 * has now been rebuilt twice (#14, #127) without a test underneath it. These
 * are that test. The third one is the case the API actually produces: `PUT
 * /api/policies` cannot delete a stored record, so a tool put back on its
 * default still returns `overridden: true` — the flag is not the question, the
 * effective mode is.
 *
 * <p>The rest are about the tabs that replaced the 12 / 6 / 0 strip (#139). One
 * of them guards a trap the run list does not have: here the reader changes the
 * very value the list is filtered by, so the row they just pressed would leave
 * the screen under their cursor and a change that worked would look like one
 * that failed.
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

const { PolicyScreen, hashForTab, tabFromHash } = await import('./PolicyScreen');

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

it('an_unknown_rule_in_the_address_falls_back_to_the_whole_table', () => {
  expect(tabFromHash('#/politikalar')).toBe('tumu');
  expect(tabFromHash('#/politikalar?kural=onay')).toBe('onay');
  expect(tabFromHash('#/politikalar?kural=uydurma')).toBe('tumu');
  expect(hashForTab('yasak')).toBe('#/politikalar?kural=yasak');
  expect(hashForTab('tumu')).toBe('#/politikalar');
});

/**
 * The strip this replaced printed 12 / 6 / 0 and could not be pressed. The number
 * has to survive the move, or the screen lost a fact to gain a control.
 */
it('each_rule_carries_the_size_of_the_group_behind_it', async () => {
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.searchIssues' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);
  await screen.findByText('jira.getIssue');

  expect(within(screen.getByRole('tab', { name: /Otomatik/ })).getByText('2')).toBeTruthy();
  expect(within(screen.getByRole('tab', { name: /Onay ister/ })).getByText('1')).toBeTruthy();
  // Nothing is forbidden, so the tab carries no number rather than a zero.
  expect(within(screen.getByRole('tab', { name: /Yasak/ })).queryByText('0')).toBeNull();
});

it('a_rule_tab_draws_only_the_tools_running_under_it', async () => {
  window.location.hash = '#/politikalar?kural=onay';
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);

  expect(await screen.findByText('jira.createIssue')).toBeTruthy();
  expect(screen.queryByText('jira.getIssue')).toBeNull();
});

it('choosing_a_rule_writes_it_to_the_address_so_the_list_can_be_linked_to', async () => {
  rows = [
    tool({ toolName: 'jira.getIssue' }),
    tool({ toolName: 'jira.createIssue', risk: 'write', mode: 'ask' }),
  ];

  render(<PolicyScreen />);
  (await screen.findByRole('tab', { name: /Onay ister/ })).click();

  await waitFor(() => expect(screen.queryByText('jira.getIssue')).toBeNull());
  expect(window.location.hash).toBe('#/politikalar?kural=onay');
});

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
