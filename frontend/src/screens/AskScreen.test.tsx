// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

/**
 * Why this file exists.
 *
 * <p>Postana sor showed a composer and then 626px of white — 70% of the screen —
 * with no empty state at all, and a placeholder that was the first example chip
 * word for word (#73). Blank space is read as "everything is connected, ask
 * away", which is a claim the screen was in no position to make.
 *
 * <p>These tests keep the space filled with something honest and keep the two
 * suggestions from collapsing back into one.
 */

vi.mock('../data', () => ({
  getAskSource: () => ({ ask: vi.fn() }),
  getRunSource: () => ({
    getGoogleStatus: async () => ({
      configured: true,
      connected: false,
      scopes: 'gmail.readonly',
      redirectUri: '',
      startUrl: '/api/oauth/google/start',
    }),
  }),
  RUN_SOURCE_KIND: 'api',
}));

const { AskScreen } = await import('./AskScreen');

beforeEach(() => localStorage.clear());
afterEach(cleanup);

it('before_the_first_question_the_screen_says_where_it_would_search', async () => {
  render(<AskScreen />);

  expect(screen.getByText('Soru nerede aranıyor')).toBeTruthy();
  // Gmail is not connected in this fixture, and the screen has to admit it
  // rather than leave a blank page implying it is ready.
  expect(await screen.findByRole('link', { name: /Gmail’i bağla/ })).toBeTruthy();
  expect(screen.getByText(/Henüz bir soru sormadın/)).toBeTruthy();
});

it('the_placeholder_is_not_one_of_the_examples_printed_under_it', () => {
  render(<AskScreen />);

  const placeholder = screen.getByLabelText('Posta kutuna sorman').getAttribute('placeholder') ?? '';
  const chips = screen
    .getAllByRole('button')
    .map((button) => button.textContent?.trim().toLowerCase() ?? '');

  expect(placeholder.length).toBeGreaterThan(0);
  expect(chips).not.toContain(placeholder.toLowerCase());
  expect(chips).not.toContain(placeholder.replace(/^Örn:\s*/i, '').replace(/\?$/, '').toLowerCase());
});

/**
 * The trace prints the query in mono under the sentence, so the screen strips the
 * server's echo of it out of the answer. It stripped the query and left the dash
 * that introduced it: "Bu sorguyla eşleşen bir şey bulamadım: Gmail — ." (#99)
 */
it('removing_the_echoed_query_does_not_leave_a_dash_holding_nothing', async () => {
  const { withoutQueryEcho } = await import('./AskScreen');
  const query = '(from:anthropic) subject:(fatura OR ödeme) newer_than:30d';

  const cleaned = withoutQueryEcho(
    `Bu sorguyla eşleşen bir şey bulamadım: Gmail — ${query}. Sorunu biraz farklı sorarsan tekrar deneyebilirim.`,
    query,
  );

  expect(cleaned).toContain('bulamadım: Gmail.');
  expect(cleaned).not.toContain('—');
  expect(cleaned).toContain('tekrar deneyebilirim');
});

it('a_dash_inside_a_sentence_is_left_where_the_author_put_it', async () => {
  const { withoutQueryEcho } = await import('./AskScreen');

  const cleaned = withoutQueryEcho(
    "Jira bağlı değil — Ayarlar'dan bağlayabilirsin. Sorguyu şöyle çevirmiştim: from:x",
    'from:x',
  );

  expect(cleaned).toContain("değil — Ayarlar'dan");
  expect(cleaned).not.toContain('from:x');
});
