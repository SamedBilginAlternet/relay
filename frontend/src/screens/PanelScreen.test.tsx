// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { PanelRejection, PanelReport } from '../types/panel';

/**
 * Why this file exists.
 *
 * <p>Panel is the tab a judge opens without being invited to, and every number on it is
 * an argument. On 1 August it made two of them badly (#54). "Red gerekçeleri" — the one
 * list that proves the approval gate is worth stopping people for — held six lines, and
 * four of them were one person pressing Durdur on four runs. And the approval rate was
 * computed against those same four, so the headline figure was partly a statement about
 * cancellations.
 *
 * <p>Both fixes are a filter and a denominator: one `if` away from being undone by a
 * refactor, with nothing else in the product noticing. These tests notice. They also pin
 * the part that is easy to get wrong in the other direction — a step a person really did
 * refuse, on a run that was stopped later, is still a refusal and must not be swept into
 * the cancellations block to make a list look cleaner.
 */

const report = vi.fn<() => Promise<PanelReport>>();

vi.mock('../data/PanelSource', () => ({
  getPanelSource: () => ({ report }),
}));

const { PanelScreen } = await import('./PanelScreen');

function line(overrides: Partial<PanelRejection> = {}): PanelRejection {
  return {
    runId: '11111111-1111-1111-1111-111111111111',
    stepId: '22222222-2222-2222-2222-222222222222',
    runGoal: 'Sprint özetini paylaş',
    runStatus: 'done',
    stepTitle: 'Slack mesajı gönder',
    toolName: 'slack.postMessage',
    reason: 'Kanal yanlış — #relay-qa olmalı.',
    at: '2026-07-30T14:12:00.000Z',
    ...overrides,
  };
}

function panel(overrides: Partial<PanelReport> = {}): PanelReport {
  return {
    from: '2026-07-25T00:00:00.000Z',
    to: '2026-08-01T00:00:00.000Z',
    runs: {
      total: 106,
      byStatus: { planning: 0, awaiting_approval: 29, running: 0, done: 50, failed: 23, cancelled: 4 },
    },
    approvals: {
      steps: 190,
      gated: 85,
      gatedRatio: 85 / 190,
      approved: 50,
      rejected: 2,
      cancelled: 4,
      pending: 29,
      approvalRate: 50 / 52,
    },
    rejections: [],
    cancellations: [],
    tools: [],
    totals: { tokens: 262_139, costUsd: 0.0537 },
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  report.mockReset();
});

it('a_stopped_run_never_appears_among_the_reasons_a_human_gave', async () => {
  report.mockResolvedValue(
    panel({
      rejections: [line()],
      cancellations: [
        line({
          stepId: 'c1',
          runStatus: 'cancelled',
          stepTitle: 'Jira kaydını güncelle',
          toolName: 'jira.updateIssue',
          reason: 'akış iptal edildi (qa+relay@samedbilgin.com)',
        }),
      ],
    }),
  );

  render(<PanelScreen />);

  const reasons = await screen.findByRole('region', { name: /Red gerekçeleri/i });
  expect(within(reasons).getByText(/Kanal yanlış/)).toBeTruthy();
  // The sentence that made four of six lines meaningless is not in this list any more.
  expect(within(reasons).queryByText(/akış iptal edildi/i)).toBeNull();

  const stopped = screen.getByRole('region', { name: /Durdurulan akışlarda kapanan adımlar/i });
  expect(within(stopped).getByText(/akış iptal edildi/i)).toBeTruthy();
});

it('a_refusal_on_a_run_that_was_stopped_later_stays_a_refusal', async () => {
  // The trap in the other direction: filtering by the run's status would move a sentence
  // a person actually typed out of the only list that carries the gate's evidence.
  report.mockResolvedValue({
    ...panel(),
    rejections: [line({ runStatus: 'cancelled', reason: 'Bu özet müşteriye kapalı bilgi içeriyor.' })],
  });

  render(<PanelScreen />);

  const reasons = await screen.findByRole('region', { name: /Red gerekçeleri/i });
  expect(within(reasons).getByText(/müşteriye kapalı bilgi/)).toBeTruthy();
  // …and it says out loud what became of the run, so the reader is not misled either.
  expect(within(reasons).getByText(/akış sonradan durduruldu/i)).toBeTruthy();
});

it('the_gate_breakdown_shows_the_stopped_steps_instead_of_dropping_them', async () => {
  // Nothing is hidden by the split: the four write-offs are still on the screen, in a
  // bucket that says what they are. 50 + 2 + 4 + 29 is the 85 printed above them.
  report.mockResolvedValue(panel());

  render(<PanelScreen />);

  const gate = await screen.findByRole('region', { name: /Onay kapısı/i });
  expect(within(gate).getByLabelText(/Akış durdurulduğu için kapandı: 4/)).toBeTruthy();
  expect(within(gate).getByLabelText(/^Reddedildi: 2/)).toBeTruthy();
});

it('an_empty_range_says_so_instead_of_drawing_a_chart_out_of_zeros', async () => {
  report.mockResolvedValue(
    panel({
      runs: { total: 0, byStatus: {} },
      approvals: {
        steps: 0,
        gated: 0,
        gatedRatio: 0,
        approved: 0,
        rejected: 0,
        cancelled: 0,
        pending: 0,
        approvalRate: 0,
      },
      totals: { tokens: 0, costUsd: 0 },
    }),
  );

  render(<PanelScreen />);

  await waitFor(() => expect(screen.getByText(/Bu aralıkta hiç akış yok/)).toBeTruthy());
  expect(screen.queryByRole('region', { name: /Durdurulan akışlarda kapanan adımlar/i })).toBeNull();
});
