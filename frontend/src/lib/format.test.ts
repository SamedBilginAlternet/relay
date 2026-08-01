import { expect, it } from 'vitest';
import { formatUsd, modelLabel } from './format';

/**
 * Why this file exists.
 *
 * <p>Relay's whole pitch is a number a judge can recompute. The backend takes that
 * literally: `CostMeter` pins every price at six decimals before it leaves the API, with a
 * comment saying why — two endpoints must not be able to disagree about the same money.
 *
 * <p>The screen then rounded to four and disagreed with all of them. On 2026-08-01 the live
 * API reported a step at `0.000113` and the panel read `$0.0001` (#111). The steps that get
 * routed to the cheap model are the smallest numbers in the product, so four decimals lost
 * the digits precisely where the cost claim is made.
 *
 * <p>These tests hold the boundary: the printed price is the price the API sent, no
 * scientific notation, and nothing unknown is ever printed as a zero.
 */

it('a_price_is_printed_with_every_digit_the_api_sent', () => {
  // Both numbers are live values from GET /api/runs on 2026-08-01.
  expect(formatUsd(0.000113)).toBe('$0.000113');
  expect(formatUsd(0.000525)).toBe('$0.000525');
});

it('a_step_cheap_enough_to_round_away_still_shows_what_it_cost', () => {
  // The cheap model's steps live down here; at four decimals this read "$0.0000".
  expect(formatUsd(0.000015)).toBe('$0.000015');
  expect(formatUsd(0.0000004)).toBe('$0.000000');
});

it('a_dollar_and_over_is_read_as_money_not_as_a_measurement', () => {
  expect(formatUsd(1.5)).toBe('$1.50');
  expect(formatUsd(12)).toBe('$12.00');
});

it('no_price_is_ever_written_in_scientific_notation', () => {
  // 0.000382 reached a screen as "3.82E-4" once, from the other side of the wire.
  for (const value of [0.000382, 0.0000001, 1e-7, 2.5e-5, 1e21]) {
    expect(formatUsd(value)).not.toMatch(/e[+-]/i);
  }
});

it('a_missing_price_is_a_dash_not_a_zero', () => {
  // "$0.00" is a claim about money. NaN is the absence of one.
  expect(formatUsd(Number.NaN)).toBe('—');
  expect(formatUsd(Number.POSITIVE_INFINITY)).toBe('—');
  expect(formatUsd(0)).toBe('$0.000000');
});

it('a_negative_amount_keeps_its_sign_outside_the_currency', () => {
  expect(formatUsd(-0.000113)).toBe('-$0.000113');
});

/**
 * The model shorthand (#112). A step row has room for two characters, not for
 * `groq:llama-3.3-70b-versatile`, and the comparison a reader is making is between sizes.
 * The danger in shortening an id is inventing one, so the rules are narrow on purpose.
 */

it('the_two_models_relay_actually_routes_between_read_as_their_sizes', () => {
  expect(modelLabel('groq:llama-3.1-8b-instant')).toBe('8B');
  expect(modelLabel('groq:llama-3.3-70b-versatile')).toBe('70B');
});

it('a_version_number_is_not_mistaken_for_a_parameter_count', () => {
  // llama-3.1-8b: the 3.1 comes first and only the 8 is followed by a "b".
  expect(modelLabel('groq:llama-3.1-8b-instant')).not.toBe('3.1B');
  expect(modelLabel('llama-4.0-405b')).toBe('405B');
});

it('an_id_with_no_size_in_it_keeps_its_own_name_instead_of_a_guess', () => {
  expect(modelLabel('stub')).toBe('stub');
  expect(modelLabel('openai:gpt-4o-mini')).toBe('gpt-4o-mini');
  expect(modelLabel('groq:mixtral-8x7b-32768')).toBe('mixtral-8x7b-32768');
});
