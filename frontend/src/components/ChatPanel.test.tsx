// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { AgentMessage, Run } from '../types/api';
import { ChatPanel, splitMachine } from './ChatPanel';

/**
 * Why this file exists.
 *
 * <p>The left column is where Relay's second claim is made: every step is
 * visible, who told whom what is readable. Until #126 it made that claim as a
 * stack of identical chat bubbles, so a tool call, a verifier's verdict, a
 * policy gate and an ordinary sentence all landed on the eye as the same
 * object. The rewrite turns it into a log, and a log is only worth having if
 * the three kinds of row keep telling themselves apart — hence a test per kind,
 * because "they look different" is exactly the property a later CSS tidy-up
 * deletes without noticing.
 *
 * <p>Two of the rules underneath are load-bearing in a way that is invisible
 * from the markup. `isToUser` matches on the raw wire ids (`user`, `kullanıcı`,
 * `sen`, `you`), not on the Turkish label — renaming an agent must never
 * reclassify a message. And the panel pins itself to the newest row on every
 * render; jsdom has no scroller, so the day someone drops the shim the pin dies
 * silently and a live run scrolls away from the reader.
 */

const NOW = '2026-08-01T07:13:00Z';

function message(overrides: Partial<AgentMessage> = {}): AgentMessage {
  return {
    id: crypto.randomUUID(),
    stepId: null,
    fromAgent: 'coordinator',
    toAgent: 'verifier',
    content: 'Adım 1 sende.',
    createdAt: NOW,
    ...overrides,
  };
}

function run(messages: AgentMessage[]): Run {
  return {
    id: '6cf275fd-ebe0-41c5-b62e-938eb148c0ec',
    goal: 'Bugünkü maillerime bak',
    status: 'done',
    costTokens: 22174,
    costUsd: 0.0078,
    budgetUsd: null,
    steps: [],
    messages,
    createdAt: NOW,
    finishedAt: NOW,
  };
}

function show(messages: AgentMessage[]) {
  return render(<ChatPanel run={run(messages)} phase="ready" error={null} readOnly />);
}

beforeEach(() => {
  // jsdom has no scroller; the log pins itself to the newest row on every render.
  Element.prototype.scrollTo = () => {};
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

afterEach(cleanup);

it('a_message_written_to_the_person_is_not_drawn_as_agent_traffic', () => {
  const { container } = show([
    message({ fromAgent: 'coordinator', toAgent: 'kullanıcı', content: 'Jira kaydı açıldı.' }),
  ]);

  const row = container.querySelector('.worklog__row--tome');
  expect(row).not.toBeNull();
  expect(container.querySelector('.worklog__row--a2a')).toBeNull();

  // It is addressed to the reader, not routed at them: no `→ <target>` on the line.
  expect(row!.querySelector('.worklog__to')).toBeNull();
  expect(row!.textContent).toContain('Jira kaydı açıldı.');
});

it('agent_to_agent_traffic_still_says_who_it_went_to', () => {
  const { container } = show([message({ fromAgent: 'verifier', toAgent: 'jira-agent' })]);

  const row = container.querySelector('.worklog__row--a2a');
  expect(row).not.toBeNull();
  expect(container.querySelector('.worklog__row--tome')).toBeNull();

  // Both ends are named in Turkish, and the destination is on the line itself.
  expect(row!.querySelector('.worklog__who')!.textContent).toBe('Doğrulayıcı');
  expect(row!.querySelector('.worklog__to')!.textContent).toBe('Jira Uzmanı');
  expect(container.textContent).not.toContain('jira-agent');
});

it('a_tool_id_inside_a_sentence_is_set_in_mono_and_the_sentence_is_not', () => {
  const { container } = show([
    message({ content: 'gmail.search tamam (156 ms), sonuç doğrulamaya gidiyor.' }),
  ]);

  const facts = [...container.querySelectorAll('code.worklog__fact')].map((e) => e.textContent);
  expect(facts).toContain('gmail.search');
  expect(facts).toContain('156 ms');

  // The Turkish clause is not swept into the machine layer with them.
  for (const fact of facts) expect(fact).not.toContain('doğrulamaya');
});

it('the_goal_and_the_answer_share_a_layer_the_record_does_not', () => {
  const { container } = show([
    message({ toAgent: 'verifier' }),
    message({ fromAgent: 'coordinator', toAgent: 'user', content: 'Bitti.' }),
  ]);

  // The ask leads the log, and it is drawn like the answer, not like the traffic.
  const rows = [...container.querySelectorAll('.worklog__row')];
  expect(rows[0]!.className).toContain('worklog__row--goal');
  expect(rows[0]!.textContent).toContain('Bugünkü maillerime bak');
  expect(container.querySelectorAll('.worklog__row--tome').length).toBe(1);
  expect(container.querySelectorAll('.worklog__row--a2a').length).toBe(1);
});

it('every_row_carries_the_time_it_happened', () => {
  const { container } = show([message(), message({ toAgent: 'you' })]);

  const times = [...container.querySelectorAll('.worklog__row')].map(
    (row) => row.querySelector('.worklog__time')?.getAttribute('datetime'),
  );
  expect(times).toEqual([NOW, NOW, NOW]);
});

it('the_recipient_is_read_from_the_wire_id_not_from_the_printed_name', () => {
  // Every spelling the backend has used for the human, including the English
  // ones. A message the run addressed to a person must never be filed as
  // machine traffic because the label in front of it was translated.
  for (const raw of ['user', 'kullanıcı', 'Sen', ' YOU ']) {
    const { container } = show([message({ toAgent: raw })]);
    expect(container.querySelector('.worklog__row--tome')).not.toBeNull();
    cleanup();
  }

  const { container } = show([message({ toAgent: 'jira-agent' })]);
  expect(container.querySelector('.worklog__row--tome')).toBeNull();
});

it('the_log_follows_the_run_instead_of_staying_where_the_reader_was', () => {
  const scrollTo = vi.fn();
  Element.prototype.scrollTo = scrollTo;

  show([message()]);

  expect(scrollTo).toHaveBeenCalled();
  expect(scrollTo.mock.calls.at(-1)![0]).toMatchObject({ top: expect.any(Number) });
});

it('splitting_a_line_never_loses_a_character_of_it', () => {
  const line =
    'jira.createIssue çağrılıyor: {"projectKey":"KAN"} — onaylayan samed.bilgin@alternet.com.tr';

  expect(splitMachine(line).map((p) => p.text).join('')).toBe(line);
});

it('an_ordinary_turkish_sentence_is_left_entirely_in_prose', () => {
  // The rules are allowed to miss a fact; they are not allowed to set a word in
  // mono, because that is a claim that the word came from a machine.
  const parts = splitMachine('Adım 4 doğrulandı: sonuç isteneni karşılıyor, akış devam ediyor.');

  expect(parts.every((p) => !p.machine)).toBe(true);
});
