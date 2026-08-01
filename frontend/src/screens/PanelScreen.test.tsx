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
 *
 * <p>The money block (#110) is here for the same reason. It answers "where did the money
 * go, and what did the routing save", and every honest version of that answer is one
 * comment away from a dishonest one: printing a comparison the server did not record,
 * keeping the difference when the counterfactual covers only some of the rows, or drawing
 * bars out of a window where nothing recorded which model answered. The tests below hold
 * the screen to saying nothing rather than saying something flattering.
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
      approvedAsIs: 44,
      approvedWithEdit: 6,
      rejected: 2,
      cancelled: 4,
      pending: 29,
      approvalRate: 50 / 52,
      editRate: 6 / 52,
    },
    rejections: [],
    cancellations: [],
    tools: [],
    models: [],
    routing: null,
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

it('an_edited_approval_is_counted_apart_from_a_straight_one', async () => {
  // The whole of #54's first half. A yes where the person rewrote the channel and a yes
  // where they read a line and pressed the button are not the same event, and one number
  // covering both was the answer that made the gate look like a rubber stamp.
  report.mockResolvedValue(panel());

  render(<PanelScreen />);

  const gate = await screen.findByRole('region', { name: /Onay kapısı/i });
  expect(within(gate).getByLabelText(/^Onaylandı: 44/)).toBeTruthy();
  expect(within(gate).getByLabelText(/^Düzeltilip onaylandı: 6/)).toBeTruthy();
  expect(within(gate).getByLabelText(/^Reddedildi: 2/)).toBeTruthy();
  // 44 + 6 + 2 = the 52 decisions the rate is computed over. Nothing lands anywhere else.
  expect(within(gate).getByText(/52 kararın/)).toBeTruthy();
});

it('the_headline_hint_names_all_three_outcomes_not_just_yes_and_no', async () => {
  report.mockResolvedValue(panel());

  render(<PanelScreen />);

  await waitFor(() => expect(screen.getByText(/44 onay · 6 düzeltilip onay · 2 red/)).toBeTruthy());
});

it('no_decisions_means_no_intervention_rate_rather_than_a_confident_zero', async () => {
  // A percentage computed over nothing is not a measurement, and "%0" reads like one.
  report.mockResolvedValue({
    ...panel(),
    approvals: {
      steps: 12,
      gated: 3,
      gatedRatio: 3 / 12,
      approved: 0,
      approvedAsIs: 0,
      approvedWithEdit: 0,
      rejected: 0,
      cancelled: 0,
      pending: 3,
      approvalRate: 0,
      editRate: 0,
    },
  });

  render(<PanelScreen />);

  const gate = await screen.findByRole('region', { name: /Onay kapısı/i });
  expect(within(gate).queryByText(/gönderilecek değeri değiştirdi/)).toBeNull();
});

const CHEAP = {
  model: 'groq:llama-3.1-8b-instant',
  calls: 34,
  tokens: 18_900,
  costUsd: 0.0043,
  premiumCostUsd: 0.0129,
};
const STRONG = {
  model: 'groq:llama-3.3-70b-versatile',
  calls: 7,
  tokens: 4_900,
  costUsd: 0.0102,
  premiumCostUsd: 0.0102,
};

it('the_model_that_carried_the_volume_is_named_with_its_own_calls_and_cost', async () => {
  // The claim the product is sold on, and until #110 the panel could not show it: one
  // total cannot say that 34 of 41 calls were answered by the cheap tier.
  report.mockResolvedValue(panel({ models: [CHEAP, STRONG] }));

  render(<PanelScreen />);

  const models = await screen.findByRole('region', { name: /Model başına çağrı ve maliyet/i });
  expect(within(models).getByLabelText(/^groq:llama-3\.1-8b-instant: 34 çağrı/)).toBeTruthy();
  expect(within(models).getByLabelText(/^groq:llama-3\.3-70b-versatile: 7 çağrı/)).toBeTruthy();
});

it('the_comparison_prints_both_prices_beside_the_difference_it_came_from', async () => {
  report.mockResolvedValue(
    panel({
      models: [CHEAP, STRONG],
      routing: {
        calls: 41,
        tokens: 23_800,
        costUsd: 0.0145,
        premiumCostUsd: 0.0231,
        differenceUsd: 0.0086,
      },
    }),
  );

  render(<PanelScreen />);

  const models = await screen.findByRole('region', { name: /Model başına çağrı ve maliyet/i });
  // All three, side by side. A difference shown on its own is a number nobody can check —
  // the point of the line is that the subtraction is visible.
  expect(within(models).getByText(/\$0\.0145/)).toBeTruthy();
  expect(within(models).getByText(/\$0\.0231/)).toBeTruthy();
  expect(within(models).getByText(/\$0\.0086/)).toBeTruthy();
  // And what it is a comparison of: the same calls and the same tokens, priced twice.
  expect(within(models).getByText(/41 çağrının aynı 23\.800 tokenı/)).toBeTruthy();
});

it('nothing_on_this_block_claims_time_saved_or_a_productivity_multiplier', async () => {
  // The metrics that cannot be derived from a token count and a price list, and are
  // therefore the ones a dashboard invents first.
  report.mockResolvedValue(
    panel({
      models: [CHEAP, STRONG],
      routing: {
        calls: 41,
        tokens: 23_800,
        costUsd: 0.0145,
        premiumCostUsd: 0.0231,
        differenceUsd: 0.0086,
      },
    }),
  );

  render(<PanelScreen />);

  const models = await screen.findByRole('region', { name: /Model başına çağrı ve maliyet/i });
  expect(within(models).queryByText(/kazanılan süre|zaman kazan|verimlilik|kat daha|× daha/i)).toBeNull();
  // Not even a tidy percentage: %63 ucuz reads as a finding and is a ratio of two sums
  // whose denominator the reader cannot see.
  expect(within(models).queryByText(/%\d/)).toBeNull();
});

it('a_window_with_no_recorded_model_says_so_instead_of_drawing_empty_bars', async () => {
  // What the live panel answers until the migration that adds `steps.model` lands.
  report.mockResolvedValue(panel({ models: [], routing: null }));

  render(<PanelScreen />);

  const models = await screen.findByRole('region', { name: /Model başına çağrı ve maliyet/i });
  expect(within(models).getByText(/hangi modelin cevapladığı kayıtlı değil/i)).toBeTruthy();
  // No bar, and above all no comparison: there is nothing to compare.
  expect(within(models).queryByRole('img')).toBeNull();
  expect(within(models).queryByText(/Tamamı güçlü modelde olsaydı/)).toBeNull();
});

it('a_missing_counterfactual_drops_the_comparison_and_keeps_the_table', async () => {
  // Which model carried the volume is knowable without a counterfactual. Hiding the table
  // to protect the claim would lose a fact; printing the claim anyway would invent one.
  report.mockResolvedValue(
    panel({ models: [{ ...CHEAP, premiumCostUsd: null }], routing: null }),
  );

  render(<PanelScreen />);

  const models = await screen.findByRole('region', { name: /Model başına çağrı ve maliyet/i });
  expect(within(models).getByLabelText(/^groq:llama-3\.1-8b-instant: 34 çağrı/)).toBeTruthy();
  expect(within(models).getByText(/güçlü model karşılaştırması kayıtlı değil/i)).toBeTruthy();
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
        approvedAsIs: 0,
        approvedWithEdit: 0,
        rejected: 0,
        cancelled: 0,
        pending: 0,
        approvalRate: 0,
        editRate: 0,
      },
      totals: { tokens: 0, costUsd: 0 },
    }),
  );

  render(<PanelScreen />);

  await waitFor(() => expect(screen.getByText(/Bu aralıkta hiç akış yok/)).toBeTruthy());
  expect(screen.queryByRole('region', { name: /Durdurulan akışlarda kapanan adımlar/i })).toBeNull();
});
