// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { Step } from '../types/api';
import { StepRow } from './StepRow';

/**
 * Why this file exists.
 *
 * <p>Relay routes each job to a tier: the cheap model answers what it can, the strong one
 * answers the rest. Every claim the product makes about cost rests on that, and until #112
 * none of it was on screen — a step showed a price with no model beside it, so a cheap step
 * and a step that got the cheap answer looked exactly alike.
 *
 * <p>The field is optional on the wire in two different ways, and both of them are easy to
 * "fix" into a lie later: a step that made no model call has none, and a server that
 * predates the field sends none either. Neither is a zero and neither is the cheap model.
 * These tests hold that unknown is drawn as nothing at all, and that the shorthand on the
 * row never becomes the only copy of the id.
 */

afterEach(cleanup);

function step(overrides: Partial<Step> = {}): Step {
  return {
    id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    ordinal: 1,
    title: 'Gelen kutusunu tara',
    role: 'gmail-agent',
    toolName: 'gmail.listMessages',
    params: { query: 'is:unread' },
    status: 'done',
    decision: 'auto',
    pausedBy: null,
    rejectReason: null,
    result: { count: 3 },
    error: null,
    tokens: 659,
    costUsd: 0.000113,
    startedAt: '2026-08-01T04:00:00Z',
    finishedAt: '2026-08-01T04:00:04Z',
    ...overrides,
  };
}

function show(s: Step, expanded = false) {
  return render(
    <StepRow step={s} index={0} expanded={expanded} onToggle={() => {}} readOnly />,
  );
}

it('a_step_says_which_model_answered_it_without_being_opened', () => {
  show(step({ model: 'groq:llama-3.1-8b-instant' }));

  // The point of the shorthand: 8B against 70B is readable at a glance down a column.
  expect(screen.getByText('8B')).toBeTruthy();
});

it('the_short_name_never_becomes_the_only_copy_of_the_model_id', () => {
  show(step({ model: 'groq:llama-3.3-70b-versatile' }));

  expect(screen.getByText('70B').getAttribute('title')).toContain('groq:llama-3.3-70b-versatile');
});

it('the_opened_step_prints_the_model_id_beside_the_price_it_produced', () => {
  show(step({ model: 'groq:llama-3.1-8b-instant' }), true);

  expect(screen.getByText('Yanıtlayan model: groq:llama-3.1-8b-instant')).toBeTruthy();
  // Same row as the money it is meant to explain, and the money is unrounded.
  expect(screen.getByText('$0.000113')).toBeTruthy();
});

it('a_step_with_no_model_call_shows_nothing_rather_than_a_placeholder', () => {
  // A tool-only step: no call, no tokens, no price — and therefore no model to name.
  show(step({ model: null, tokens: 0, costUsd: 0 }), true);

  expect(screen.queryByText(/Yanıtlayan model/)).toBeNull();
  expect(screen.queryByText(/^—$/)).toBeNull();
  expect(screen.queryByText(/bilinmiyor/i)).toBeNull();
});

it('a_server_that_never_heard_of_the_field_draws_no_model_at_all', () => {
  // The field is absent, not null: this is every step in the database before the migration.
  const { model: _dropped, ...withoutTheField } = step({ model: 'groq:llama-3.1-8b-instant' });
  show(withoutTheField as Step, true);

  expect(screen.queryByText(/Yanıtlayan model/)).toBeNull();
  expect(screen.queryByText('8B')).toBeNull();
  // …and the rest of the row is untouched by the missing field.
  expect(screen.getByText('gmail.listMessages')).toBeTruthy();
});

it('opening_a_step_is_still_what_shows_the_parameters', () => {
  // Guards the assumption the tests above rest on: the body is what the chip is short for.
  const onToggle: string[] = [];
  render(
    <StepRow
      step={step({ model: 'groq:llama-3.1-8b-instant' })}
      index={0}
      expanded={false}
      onToggle={(id) => onToggle.push(id)}
      readOnly
    />,
  );

  fireEvent.click(screen.getByRole('button'));
  expect(onToggle).toEqual(['aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee']);
});
