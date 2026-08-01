import { expect, it } from 'vitest';
import { approvalFunnel } from './funnel';
import type { PanelApprovals } from '../types/panel';

/**
 * Why this file exists.
 *
 * <p>A funnel is a picture that makes a claim the numbers may not support: that each
 * stage is *contained* in the one above it, and that the terminal buckets are all of the
 * last one. Drawn from five independent counters, that claim is an assumption. If the
 * server ever sends buckets that do not add up — a migration half-applied, a status the
 * screen has not heard of — a funnel drawn by dividing each value by the total will look
 * exactly as tidy as a correct one. That is worse than the table it replaced.
 *
 * <p>So the arithmetic lives here, in a pure function, with the remainder carried out as
 * a number the screen has to print. These tests pin the two directions that matter: a
 * consistent window adds up to zero remainder, and an inconsistent one is reported rather
 * than rescaled into agreement.
 */

function approvals(overrides: Partial<PanelApprovals> = {}): PanelApprovals {
  return {
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
    ...overrides,
  };
}

it('the_five_outcomes_add_up_to_the_gate_they_are_drawn_inside', () => {
  const funnel = approvalFunnel(approvals());

  const sum = funnel.slices.reduce((total, slice) => total + slice.value, 0);
  expect(sum).toBe(85);
  expect(sum).toBe(funnel.gated);
  expect(funnel.unaccounted).toBe(0);
  // …and the shares of those five are one whole, not one whole plus rounding.
  expect(funnel.slices.reduce((total, slice) => total + slice.share, 0)).toBeCloseTo(1, 10);
});

it('buckets_that_do_not_reach_the_gate_are_reported_not_rescaled', () => {
  // The failure mode this function exists for: four of the five buckets, which a chart
  // that divides by their own sum would draw as a full bar.
  const funnel = approvalFunnel(approvals({ pending: 20 }));

  expect(funnel.unaccounted).toBe(9);
  // The shares still measure against `gated`, so the bar visibly stops short.
  expect(funnel.slices.reduce((total, slice) => total + slice.share, 0)).toBeCloseTo(76 / 85, 10);
});

it('buckets_that_overrun_the_gate_are_reported_with_a_negative_remainder', () => {
  const funnel = approvalFunnel(approvals({ cancelled: 10 }));

  expect(funnel.unaccounted).toBe(-6);
});

it('every_stage_is_narrower_than_the_one_above_it', () => {
  // The containment the shape asserts: steps ⊇ gated ⊇ decided ⊇ approved.
  const { stages } = approvalFunnel(approvals());

  expect(stages.map((stage) => stage.value)).toEqual([190, 85, 52, 50]);
  for (let i = 1; i < stages.length; i += 1) {
    expect(stages[i]!.value).toBeLessThanOrEqual(stages[i - 1]!.value);
    expect(stages[i]!.share).toBeLessThanOrEqual(stages[i - 1]!.share);
  }
});

it('each_stage_says_what_it_kept_of_the_stage_above_it', () => {
  const { stages } = approvalFunnel(approvals());

  // The first has nothing above it and claims no ratio — a leading "%100" would be a
  // number about nothing.
  expect(stages[0]!.ofPrevious).toBeNull();
  expect(stages[1]!.ofPrevious).toBeCloseTo(85 / 190, 10);
  expect(stages[2]!.ofPrevious).toBeCloseTo(52 / 85, 10);
  // The last one is the approval rate, arrived at from the other end of the data.
  expect(stages[3]!.ofPrevious).toBeCloseTo(50 / 52, 10);
});

it('an_empty_window_divides_by_nothing_and_draws_nothing', () => {
  const funnel = approvalFunnel(
    approvals({
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
    }),
  );

  expect(funnel.stages.every((stage) => stage.share === 0)).toBe(true);
  expect(funnel.slices.every((slice) => slice.share === 0)).toBe(true);
  expect(funnel.unaccounted).toBe(0);
  // No NaN reaches the drawing code — a NaN width is an invisible bar, not a visible bug.
  expect(funnel.stages.every((stage) => Number.isFinite(stage.share))).toBe(true);
});

it('no_series_on_this_screen_is_painted_with_the_state_accent', () => {
  // Violet means "this is a state you are in" everywhere in this product. Six tool bars
  // used to be filled with it. The funnel must not reintroduce it.
  const funnel = approvalFunnel(approvals());

  for (const slice of funnel.slices) {
    expect(slice.color).toMatch(/^var\(--chart-/);
    expect(slice.color).not.toMatch(/accent/);
  }
});
