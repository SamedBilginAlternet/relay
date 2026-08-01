// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Route } from '../lib/router';
import type { PanelRange, PanelReport } from '../types/panel';

/**
 * Why this file exists.
 *
 * <p>Issue #72, third part: a run that stops for a decision was invisible from everywhere
 * except the screen it was started on, and 32 of them had piled up on the live box with
 * nothing anywhere saying so. The top bar is the only thing on screen no matter which
 * screen you are on, so the number goes there.
 *
 * <p>The tests are about the two ways a badge like this rots. It can lie downwards — the
 * obvious source, `GET /api/runs`, hands back its default page of twenty rows, which on
 * that same box held 3 of the 32; a badge is only worth having if its number is the whole
 * number. And it can lie upwards by rendering a stale or invented count: an unread count
 * must never appear as a confident zero, and a failed read must not empty the queue.
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
    totals: { tokens: 0, costUsd: 0 },
  };
}

function show(route: Route = { name: 'today' }, onNavigate = vi.fn()) {
  const view = render(<AppHeader route={route} onNavigate={onNavigate} />);
  return { ...view, onNavigate };
}

const badge = () => screen.queryByRole('button', { name: /onayını bekliyor/i });

afterEach(() => {
  cleanup();
  report.mockReset();
});

it('a_run_parked_on_a_person_is_visible_from_a_screen_that_knows_nothing_about_it', async () => {
  report.mockResolvedValue(panel(32));

  // Politikalar has no run on it at all; the badge is not the screen's news, it is the
  // product's.
  show({ name: 'policies' });

  const chip = await screen.findByRole('button', { name: '32 akış onayını bekliyor. Geçmiş ekranını aç.' });
  expect(chip.textContent).toContain('32');
});

it('an_empty_queue_shows_no_badge_rather_than_a_zero', async () => {
  report.mockResolvedValue(panel(0));

  show();

  await waitFor(() => expect(report).toHaveBeenCalled());
  expect(badge()).toBeNull();
});

it('nothing_is_claimed_before_the_first_count_comes_back', () => {
  report.mockReturnValue(new Promise(() => {}));

  show();

  expect(badge()).toBeNull();
});

it('the_count_covers_the_whole_history_not_the_first_page_of_it', async () => {
  report.mockResolvedValue(panel(32));

  show();

  await waitFor(() => expect(report).toHaveBeenCalled());
  // `GET /api/runs` answers with twenty rows and would have counted 3 of these 32. The
  // window asked for here has to be long enough that "how many are waiting" is a count.
  const from = Date.parse(report.mock.calls[0]![0].from ?? '');
  expect((Date.now() - from) / 86_400_000).toBeGreaterThan(300);
});

it('pressing_the_badge_lands_on_the_screen_where_the_decision_can_be_taken', async () => {
  report.mockResolvedValue(panel(4));
  const onNavigate = vi.fn();

  show({ name: 'today' }, onNavigate);
  fireEvent.click(await screen.findByRole('button', { name: /onayını bekliyor/i }));

  expect(onNavigate).toHaveBeenCalledWith('#/history');
});

it('a_count_that_could_not_be_read_does_not_become_an_empty_queue', async () => {
  report.mockResolvedValue(panel(7));
  const { rerender } = show();
  await screen.findByRole('button', { name: /7 akış/i });

  report.mockRejectedValue(new Error('Sunucuya ulaşılamadı'));
  // A navigation is what re-counts, so navigating is what re-runs the failing read.
  rerender(<AppHeader route={{ name: 'panel' }} onNavigate={vi.fn()} />);

  await waitFor(() => expect(report).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('button', { name: /7 akış/i })).toBeTruthy();
});
