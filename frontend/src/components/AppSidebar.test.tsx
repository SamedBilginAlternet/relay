// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { parseHash } from '../lib/router';
import type { Route } from '../lib/router';
import type { RunSummary } from '../types/api';
import type { PanelRange, PanelReport } from '../types/panel';

/**
 * Why this file exists.
 *
 * <p>Issue #130. The product carried two navigation surfaces at the same level — a
 * centred strip of six tabs and, on one screen out of seven, a rail of live runs. The
 * refactor makes one, and three of its claims are the kind that rot quietly.
 *
 * <p>The first is the count. Two surfaces printing two different numbers for "how many
 * flows are waiting on you" is not a hypothetical: it was issue #100. So the badge here
 * and the badge on the top bar have to be the same number from the same request, and a
 * test says so with both of them on screen at once.
 *
 * <p>The second is the collapsed rail. Icon-only navigation costs discoverability, and
 * the deal that buys it back is a name on every control and a badge that does not
 * disappear. Both are asserted with the rail collapsed, because a count that hides when
 * the column narrows is the silent pile-up this product exists to prevent.
 *
 * <p>The third is where you are. Colour alone is not a state marker for somebody who
 * cannot separate two hues, so the current item carries a class that also carries weight
 * and an edge marker — and `aria-current`, which is what a screen reader reads.
 *
 * <p>The live runs moved here from Sohbet in the same issue, and the claims that came
 * with them are asserted from a screen that is not Sohbet: on the live box 28 flows were
 * stopped on a decision while the rail that listed them existed on the one screen you had
 * to already be on to see it.
 */

const report = vi.fn<(range: PanelRange) => Promise<PanelReport>>();
const listRuns = vi.fn<(o?: { status?: string; size?: number }) => Promise<RunSummary[]>>();

vi.mock('../data/PanelSource', () => ({ getPanelSource: () => ({ report }) }));
vi.mock('../data', () => ({
  getRunSource: () => ({ listRuns, streamRun: () => () => {} }),
  RUN_SOURCE_KIND: 'api',
}));

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= NoopResizeObserver as unknown as typeof ResizeObserver;

const { AppSidebar } = await import('./AppSidebar');
const { AppHeader } = await import('./AppHeader');

function panel(awaiting: number): PanelReport {
  return {
    from: '2025-08-05T00:00:00Z',
    to: '2026-08-01T00:00:00Z',
    runs: {
      total: 144,
      byStatus: {
        planning: 0,
        awaiting_approval: awaiting,
        running: 0,
        done: 71,
        failed: 25,
        cancelled: 16,
      },
    },
    approvals: {
      steps: 246,
      gated: 108,
      gatedRatio: 0.43,
      approved: 52,
      approvedAsIs: 40,
      approvedWithEdit: 12,
      rejected: 24,
      cancelled: 3,
      pending: awaiting,
      approvalRate: 0.68,
      editRate: 12 / 52,
    },
    rejections: [],
    cancellations: [],
    tools: [],
    models: [],
    routing: null,
    totals: { tokens: 0, costUsd: 0 },
  };
}

function show(over: Partial<Parameters<typeof AppSidebar>[0]> = {}) {
  const onNavigate = vi.fn();
  const view = render(
    <AppSidebar route={{ name: 'today' }} onNavigate={onNavigate} variant="rail" {...over} />,
  );
  return { ...view, onNavigate };
}

const DESTINATIONS = ['Bugün', 'Akışlar', 'Ekip', 'Panel', 'Bağlantılar', 'Politikalar'];

function parked(id: string, goal: string): RunSummary {
  return {
    id,
    goal,
    status: 'awaiting_approval',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    createdAt: '2026-08-01T08:00:00Z',
    finishedAt: null,
    stepCount: 4,
    doneStepCount: 1,
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
    // `motion` still reaches for the deprecated pair to read prefers-reduced-motion.
    addListener: () => {},
    removeListener: () => {},
  }));
});

afterEach(() => {
  cleanup();
  report.mockReset();
});

it('every_destination_is_a_button_the_keyboard_reaches_in_the_order_it_is_read', () => {
  report.mockResolvedValue(panel(0));

  show();

  const items = [...document.querySelectorAll<HTMLElement>('.sb__item')];
  // Buttons, not divs with click handlers: that is the whole of "reachable by keyboard".
  expect(items.every((el) => el.tagName === 'BUTTON')).toBe(true);
  expect(items.map((el) => el.textContent)).toEqual(DESTINATIONS);
  // Nothing in the column is taken out of the tab order.
  expect(items.some((el) => el.getAttribute('tabindex') === '-1')).toBe(false);
});

it('the_screen_you_are_on_is_marked_by_more_than_a_colour', () => {
  report.mockResolvedValue(panel(0));

  show({ route: { name: 'panel' } });

  const current = document.querySelectorAll('.sb__item--current');
  expect(current).toHaveLength(1);
  expect(current[0]?.textContent).toBe('Panel');
  // The class is what carries the weight and the edge marker; `aria-current` is what a
  // screen reader hears. Both, or the mark is only for people who can see the tint.
  expect(current[0]?.getAttribute('aria-current')).toBe('page');
});

it('a_run_detail_still_marks_the_list_it_was_opened_from', () => {
  report.mockResolvedValue(panel(0));

  show({ route: { name: 'history-detail', runId: 'r-1' } });

  expect(document.querySelector('.sb__item--current')?.textContent).toBe('Akışlar');
});

it('the_waiting_count_beside_akislar_is_the_number_the_top_bar_prints', async () => {
  report.mockResolvedValue(panel(32));
  const route: Route = { name: 'today' };

  // Both surfaces on screen at once, which is exactly the situation that produced #100:
  // a badge counted one way and a badge counted another disagreeing in public.
  render(
    <>
      <AppHeader route={route} onNavigate={vi.fn()} onOpenNav={vi.fn()} navOpen={false} />
      <AppSidebar route={route} onNavigate={vi.fn()} variant="rail" />
    </>,
  );

  await waitFor(() => expect(document.querySelector('.sb__badge')).not.toBeNull());
  const onTheBar = document.querySelector('.gate-badge__count')?.textContent;
  const onTheRail = document.querySelector('.sb__badge')?.textContent;
  expect(onTheRail).toBe('32');
  expect(onTheRail).toBe(onTheBar);
});

it('an_empty_queue_puts_no_zero_beside_akislar', async () => {
  report.mockResolvedValue(panel(0));

  show();

  await waitFor(() => expect(report).toHaveBeenCalled());
  // A badge that is always there stops being read, and a zero is a claim that the app is
  // idle made by the one thing whose job is to say when it is not.
  expect(document.querySelector('.sb__badge')).toBeNull();
});

it('a_collapsed_rail_still_names_every_control_and_still_shows_the_count', async () => {
  report.mockResolvedValue(panel(7));

  show({ collapsed: true, onToggleCollapse: vi.fn() });

  await waitFor(() => expect(document.querySelector('.sb__badge')).not.toBeNull());
  // The label is clipped by CSS, never removed, so it still opens the accessible name of
  // every control. Akışlar's name carries the count after it, which is the whole point:
  // what a sighted user reads off the badge is what a screen reader hears.
  for (const label of DESTINATIONS) {
    expect(screen.getByRole('button', { name: new RegExp(`^${label}`) })).not.toBeNull();
  }
  expect(screen.getByRole('button', { name: /^Akışlar/ }).textContent).toBe('Akışlar7');
  // And a tooltip on every one of them, which is the price of dropping to icons.
  const items = [...document.querySelectorAll<HTMLElement>('.sb__item')];
  expect(items.map((el) => el.dataset.tip)).toEqual(DESTINATIONS);
  expect(document.querySelector('.sb__badge')?.textContent).toBe('7');
});

it('the_primary_action_starts_a_new_flow_rather_than_reopening_one', () => {
  report.mockResolvedValue(panel(0));

  const { onNavigate } = show();
  fireEvent.click(screen.getByRole('button', { name: 'Yeni iş' }));

  // Bare `#/sohbet`: no run named, which is what the screen there reads as "start one".
  expect(onNavigate).toHaveBeenCalledWith('#/sohbet');
});

it('the_ekip_item_points_at_an_address_the_router_understands', () => {
  report.mockResolvedValue(panel(0));

  show({ route: parseHash('#/ekip') });

  // The item and the screen behind it were built by two agents at the same time, so the
  // one thing that has to be asserted is that they meet: a nav item pointing at a hash
  // nobody parses lands silently on Bugün, which reads as a broken link.
  expect(parseHash('#/ekip')).toEqual({ name: 'crew' });
  expect(document.querySelector('.sb__item--current')?.textContent).toBe('Ekip');
});

it('the_drawer_closes_on_escape_and_hands_nothing_to_the_page_behind_it', async () => {
  report.mockResolvedValue(panel(0));
  const onClose = vi.fn();

  render(
    <AppSidebar route={{ name: 'today' }} onNavigate={vi.fn()} variant="drawer" onClose={onClose} />,
  );

  // It is a modal layer, so it says so, and the keyboard lands inside it.
  const drawer = document.querySelector('.sb--drawer');
  expect(drawer?.getAttribute('role')).toBe('dialog');
  expect(drawer?.getAttribute('aria-modal')).toBe('true');
  await waitFor(() => expect(drawer?.contains(document.activeElement)).toBe(true));

  fireEvent.keyDown(document, { key: 'Escape' });
  expect(onClose).toHaveBeenCalled();
});

it('choosing_a_destination_in_the_drawer_closes_it', () => {
  report.mockResolvedValue(panel(0));
  const onNavigate = vi.fn();
  const onClose = vi.fn();

  render(
    <AppSidebar
      route={{ name: 'today' }}
      onNavigate={onNavigate}
      variant="drawer"
      onClose={onClose}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Politikalar' }));

  expect(onNavigate).toHaveBeenCalledWith('#/politikalar');
  // A drawer left open over the screen it just navigated to is a second copy of the
  // navigation, which is the thing this refactor removes.
  expect(onClose).toHaveBeenCalled();
});

it('a_flow_stopped_on_a_decision_is_on_screen_from_a_screen_that_is_not_sohbet', async () => {
  report.mockResolvedValue(panel(1));
  listRuns.mockImplementation(async (options) =>
    options?.status === 'awaiting_approval' ? [parked('r-b', 'Kararını bekleyen öteki iş')] : [],
  );

  // Politikalar has no run on it at all, which is the point: the flows that are alive are
  // the app's news, not one screen's.
  const { onNavigate } = show({ route: { name: 'policies' } });

  const row = await screen.findByTitle('Kararını bekleyen öteki iş');
  // The move kept the counts the rail was rebuilt for in #129: how far along, not just how
  // many steps there are.
  expect(row.textContent).toContain('1/4 adım');

  fireEvent.click(row);
  expect(onNavigate).toHaveBeenCalledWith('#/sohbet/r-b');
});

it('with_nothing_alive_the_column_carries_no_empty_section', async () => {
  report.mockResolvedValue(panel(0));
  listRuns.mockResolvedValue([]);

  show();

  await waitFor(() => expect(listRuns).toHaveBeenCalled());
  // An empty list under a heading says exactly what no section says, and costs a rule and
  // a line to say it.
  expect(document.querySelector('.rail')).toBeNull();
  expect(document.querySelector('.sb__live')).toBeNull();
});

it('the_row_marked_open_is_the_one_the_address_names', async () => {
  report.mockResolvedValue(panel(1));
  listRuns.mockImplementation(async (options) =>
    options?.status === 'awaiting_approval'
      ? [parked('r-a', 'Açık olan iş'), parked('r-b', 'Öteki iş')]
      : [],
  );

  show({ route: { name: 'chat', runId: 'r-a' } });

  await screen.findByTitle('Açık olan iş');
  const marked = document.querySelectorAll('[aria-current="true"]');
  expect(marked).toHaveLength(1);
  expect(marked[0]?.textContent).toContain('Açık olan iş');
});

it('nothing_is_marked_open_while_you_are_looking_at_another_screen', async () => {
  report.mockResolvedValue(panel(1));
  listRuns.mockImplementation(async (options) =>
    options?.status === 'awaiting_approval' ? [parked('r-a', 'Açık olan iş')] : [],
  );

  // The store keeps the last run it loaded for as long as the tab lives. Marking its row
  // current from Panel would claim a flow is on screen when the screen is something else.
  show({ route: { name: 'panel' } });

  await screen.findByTitle('Açık olan iş');
  expect(document.querySelectorAll('[aria-current="true"]')).toHaveLength(0);
});

it('a_collapsed_row_still_says_which_flow_it_is_and_how_far_along', async () => {
  report.mockResolvedValue(panel(1));
  listRuns.mockImplementation(async (options) =>
    options?.status === 'awaiting_approval' ? [parked('r-b', 'Kararını bekleyen iş')] : [],
  );

  show({ collapsed: true, onToggleCollapse: vi.fn() });

  // 68px cannot hold a goal, so the whole sentence — flow, status, progress — is in the
  // tooltip. Without it the collapsed rail is a column of coloured dots.
  const row = await screen.findByTitle('Kararını bekleyen iş — Onay bekliyor · 1/4 adım');
  // And the text is clipped rather than removed, so the row keeps its accessible name.
  expect(row.textContent).toContain('Kararını bekleyen iş');
});
