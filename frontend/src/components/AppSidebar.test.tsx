// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { parseHash } from '../lib/router';
import type { Route } from '../lib/router';
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
 */

const report = vi.fn<(range: PanelRange) => Promise<PanelReport>>();

vi.mock('../data/PanelSource', () => ({ getPanelSource: () => ({ report }) }));
vi.mock('../data', () => ({ getRunSource: () => ({}) }));

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
