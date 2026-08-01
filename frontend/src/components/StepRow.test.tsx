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

/**
 * A step parked at the gate is not working. Live it read "27 dk 56 sn" beside a
 * step whose tool had already returned and which was waiting on a person — true
 * as elapsed time, false as everything a duration means on this screen, where
 * every other row's figure is how long a tool took.
 */
it('a_parked_step_says_it_is_waiting_rather_than_working', () => {
  const started = new Date(Date.now() - 90_000).toISOString();
  render(
    <ul>
      <StepRow
        step={step({ status: 'awaiting_approval', startedAt: started, finishedAt: null })}
        index={0}
        expanded={false}
        onToggle={() => {}}
      />
    </ul>,
  );

  expect(screen.getByText(/bekliyor/)).toBeTruthy();
});

/**
 * The live incident behind `skipped` (2026-08-01 17:36): zero qualifying mails, and the
 * Jira write failed the whole run trying to draft a record for a mail that did not exist.
 * A skip is correct when the model is right and silent data loss when it is wrong, so the
 * reason must be readable on the row — a wrong skip hidden behind a click is not visible,
 * it is lost. Muted and glyph-carried, never colour alone (DESIGN.md §1).
 */
it('a_skipped_step_shows_its_reason_without_being_opened', () => {
  show(
    step({
      status: 'skipped',
      toolName: 'jira.createIssue',
      result: { skipped: true, reason: 'Bugünkü maillerde iş talebi bulunamadı.' },
      skipReason: 'Bugünkü maillerde iş talebi bulunamadı.',
    }),
  );

  expect(screen.getByText(/Atlandı: Bugünkü maillerde iş talebi bulunamadı\./)).toBeTruthy();
});

/**
 * The live incident behind `warning` (2026-08-01, run 85f1b3be): a goal asking for a note
 * on the Notion decision log was planned as a Jira status change — the planner executed
 * the note's own payload — and the human at the gate approved it, because the gate showed
 * a plausible write and nothing else. The coverage check's sentence must be on the gate
 * itself: it is the one line a person who reads nothing else has to trip over.
 */
it('the_gate_draws_the_drift_warning_where_the_approving_human_reads', () => {
  render(
    <ul>
      <StepRow
        step={step({
          status: 'awaiting_approval',
          pausedBy: 'policy',
          toolName: 'jira.updateIssue',
          params: { issueKey: 'KAN-32', status: 'Done' },
          warning: 'Bu adım hedefte anılmayan bir yüzeye yazıyor (Jira). Eminsen onayla.',
        })}
        index={0}
        expanded={false}
        onToggle={() => {}}
        onApprove={() => {}}
        onReject={() => {}}
      />
    </ul>,
  );

  expect(
    screen.getByText(/Bu adım hedefte anılmayan bir yüzeye yazıyor \(Jira\)\. Eminsen onayla\./),
  ).toBeTruthy();
});

it('a_step_without_a_warning_shows_no_drift_sentence', () => {
  // Both optionalities at once: `warning` absent (pre-migration server) on a parked step.
  render(
    <ul>
      <StepRow
        step={step({ status: 'awaiting_approval', pausedBy: 'policy' })}
        index={0}
        expanded={false}
        onToggle={() => {}}
        onApprove={() => {}}
        onReject={() => {}}
      />
    </ul>,
  );

  expect(screen.queryByText(/hedefte anılmayan/)).toBeNull();
});

it('an_opened_skipped_step_explains_itself_instead_of_dumping_the_skip_record', () => {
  show(
    step({
      status: 'skipped',
      toolName: 'jira.createIssue',
      result: { skipped: true, reason: 'Bugünkü maillerde iş talebi bulunamadı.' },
      skipReason: 'Bugünkü maillerde iş talebi bulunamadı.',
    }),
    true,
  );

  // The sentence, twice if need be — never the raw {"skipped":true,…} JSON as "Sonuç".
  expect(screen.queryByText('Sonuç')).toBeNull();
  expect(screen.getByText(/Araç hiç çağrılmadı\./)).toBeTruthy();
});
