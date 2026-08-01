// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { Run, Step } from '../types/api';
import { comparePremium, WorkflowPanel } from './WorkflowPanel';

/**
 * Why this file exists.
 *
 * <p>This is the one sentence in the product that puts a number on the routing: what the
 * run's steps cost, against what the same tokens would have cost billed entirely at the
 * strong model's price. Relay's pitch is that a judge can recompute every figure it shows,
 * so this line is the easiest place in the codebase to destroy that — an average, a
 * rounding, a missing step on one side of the sum, and it becomes a marketing claim.
 *
 * <p>So the tests below are mostly about when the line must NOT be drawn: a run where
 * nothing would have been different, a run where one step spent money that has no premium
 * price to compare against, a server that never sent the field. Silence is the correct
 * output in all three, and each of them is one careless `?? 0` away from becoming a boast.
 */

afterEach(cleanup);

function step(overrides: Partial<Step> = {}): Step {
  return {
    id: crypto.randomUUID(),
    ordinal: 1,
    title: 'Gelen kutusunu tara',
    role: 'gmail-agent',
    toolName: 'gmail.listMessages',
    params: {},
    status: 'done',
    decision: 'auto',
    pausedBy: null,
    rejectReason: null,
    result: null,
    error: null,
    tokens: 659,
    costUsd: 0.000113,
    startedAt: '2026-08-01T04:00:00Z',
    finishedAt: '2026-08-01T04:00:04Z',
    ...overrides,
  };
}

function run(steps: Step[]): Run {
  return {
    id: '11111111-2222-3333-4444-555555555555',
    goal: 'Bugünkü maillerime bak',
    status: 'done',
    costTokens: 7865,
    costUsd: 0.00475,
    budgetUsd: null,
    steps,
    messages: [],
    createdAt: '2026-08-01T04:00:00Z',
    finishedAt: '2026-08-01T04:01:00Z',
  };
}

function show(r: Run) {
  return render(
    <WorkflowPanel
      run={r}
      phase="ready"
      error={null}
      streamStatus="idle"
      expandedStepId={null}
      readOnly
      onToggleStep={() => {}}
    />,
  );
}

it('the_difference_is_the_two_sums_subtracted_and_nothing_else', () => {
  const result = comparePremium([
    step({ costUsd: 0.000113, premiumCostUsd: 0.000396 }),
    step({ costUsd: 0.000525, premiumCostUsd: 0.000525 }),
  ]);

  expect(result).toEqual({
    steps: 2,
    actualUsd: 0.000113 + 0.000525,
    premiumUsd: 0.000396 + 0.000525,
    differenceUsd: 0.000396 + 0.000525 - (0.000113 + 0.000525),
  });
});

it('a_run_that_was_all_on_the_strong_model_claims_no_saving_at_all', () => {
  // Nothing was routed anywhere cheaper, so there is nothing to compare and no line.
  const strong = [
    step({ costUsd: 0.000672, premiumCostUsd: 0.000672 }),
    step({ costUsd: 0.000256, premiumCostUsd: 0.000256 }),
  ];

  expect(comparePremium(strong)).toBeNull();

  show(run(strong));
  expect(screen.queryByText(/güçlü modelde/)).toBeNull();
});

it('a_step_that_spent_money_with_no_premium_price_cancels_the_line', () => {
  // Its cost would land on one side of the sum and be missing from the other. Two totals
  // that are not about the same work are worse than none.
  expect(
    comparePremium([
      step({ costUsd: 0.000113, premiumCostUsd: 0.000396 }),
      step({ costUsd: 0.000525, premiumCostUsd: null }),
    ]),
  ).toBeNull();
});

it('a_step_that_spent_nothing_is_not_what_stops_the_comparison', () => {
  // Pending and tool-only steps have no price and no premium; they are simply not in it.
  const result = comparePremium([
    step({ costUsd: 0.000113, premiumCostUsd: 0.000396 }),
    step({ status: 'pending', tokens: 0, costUsd: 0 }),
  ]);

  expect(result?.steps).toBe(1);
});

it('a_run_from_a_server_without_the_field_says_nothing_about_models', () => {
  show(run([step(), step({ costUsd: 0.000525 })]));

  expect(screen.queryByText(/güçlü modelde/)).toBeNull();
  expect(screen.queryByText(/fark/)).toBeNull();
});

it('the_line_prints_both_totals_and_the_difference_between_them', () => {
  show(
    run([
      step({ costUsd: 0.000113, premiumCostUsd: 0.000396 }),
      step({ costUsd: 0.000206, premiumCostUsd: 0.000721 }),
    ]),
  );

  const line = screen.getByText(/güçlü modelde/);
  // 0.000319 spent, 0.001117 at the strong model's price, 0.000798 between them — and the
  // reader can do that subtraction on the screen, which is the entire point.
  expect(line.textContent).toContain('$0.000319');
  expect(line.textContent).toContain('$0.001117');
  expect(line.textContent).toContain('$0.000798');
});

it('the_line_compares_prices_and_never_claims_a_minute_was_saved', () => {
  show(run([step({ costUsd: 0.000113, premiumCostUsd: 0.000396 })]));

  const line = screen.getByText(/güçlü modelde/);
  expect(line.textContent).not.toMatch(/dakika|saat|saniye|hız|verim|kat daha/i);
  // And it is honest about which steps it is made of, since the run's own total also
  // carries calls that belong to no step.
  expect(line.textContent).toContain('adımı');
});
