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
 * are that test. The last one is the case the API actually produces: `PUT
 * /api/policies` cannot delete a stored record, so a tool put back on its
 * default still returns `overridden: true` — the flag is not the question, the
 * effective mode is.
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

const { PolicyScreen } = await import('./PolicyScreen');

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
