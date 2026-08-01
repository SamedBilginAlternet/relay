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
