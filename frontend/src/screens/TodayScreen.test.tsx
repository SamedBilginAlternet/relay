// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ApiError } from '../data/ApiRunSource';
import type { Brief } from '../types/brief';

/**
 * Why this file exists.
 *
 * <p>Bugün used to fail in its own words. It kept a private `Failure` type, its
 * own `notice--danger` box and a `{error.message}` straight onto the screen —
 * the exact line #63 removed from Panel and Politikalar, still here, one bad
 * exception away from putting "Failed to fetch" in the middle of a Turkish
 * product. Converging it onto the shared `LoadError` is a refactor, and a
 * refactor is precisely the change that can quietly take a button away.
 *
 * <p>So these tests hold what the screen promised before the move: a Turkish
 * sentence with no machine detail in it, a way to try again when trying again
 * could work, and no button at all when the server has already said no.
 */

const getBrief = vi.fn<() => Promise<Brief>>();

vi.mock('../data', () => ({
  RUN_SOURCE_KIND: 'api',
  getBriefSource: () => ({
    getBrief,
    refreshBrief: getBrief,
    startFromSuggestion: vi.fn(),
  }),
}));

vi.mock('../data/PlaybookSource', () => ({
  getPlaybookSource: () => ({ list: async () => [], run: vi.fn() }),
}));

const { TodayScreen } = await import('./TodayScreen');

afterEach(() => {
  cleanup();
  getBrief.mockReset();
});

async function renderFailing(error: unknown) {
  getBrief.mockRejectedValue(error);
  render(<TodayScreen onNavigate={() => {}} />);
  return await screen.findByRole('alert');
}

it('an_unreachable_server_is_explained_in_turkish_with_a_way_to_try_again', async () => {
  const box = await renderFailing(
    new ApiError('Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.', 0),
  );

  expect(box.textContent ?? '').toContain(
    'Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.',
  );
  expect(screen.getByRole('button', { name: /Tekrar dene/ })).toBeTruthy();
});

it('a_request_the_server_refused_offers_no_button_that_cannot_work', async () => {
  // 403 is the server having read the request and said no. Pressing the same
  // button gets the same answer, so there is no button.
  await renderFailing(new ApiError('Bu bilgiyi görme yetkin yok.', 403));

  await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
  expect(screen.queryByRole('button', { name: /Tekrar dene/ })).toBeNull();
});

it('a_server_error_still_offers_a_way_to_try_again', async () => {
  await renderFailing(new ApiError('Brifing alınamadı (HTTP 502).', 502));

  expect(screen.getByRole('button', { name: /Tekrar dene/ })).toBeTruthy();
});

it('the_error_box_never_repeats_the_browsers_own_english_words', async () => {
  const box = await renderFailing(new TypeError('Failed to fetch'));

  expect(box.textContent ?? '').not.toMatch(/failed to fetch/i);
  expect(box.textContent ?? '').toContain(
    'Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.',
  );
});

it('the_error_box_never_names_a_build_variable_the_reader_cannot_set', async () => {
  const box = await renderFailing(
    new ApiError('VITE_API_BASE_URL ayarlanmamış', 500),
  );

  expect(box.textContent ?? '').not.toMatch(/VITE_/);
});

/**
 * Bugün is the only screen whose column is declared by its own class. Both
 * classes set 1040px, but `scrollbar-gutter: stable` hangs off the modifier
 * alone, so without it this screen sat 5px left of the other five and the nav
 * moved every time you clicked into it (#69).
 */
it('the_column_is_declared_the_same_way_as_every_other_screen', async () => {
  getBrief.mockRejectedValue(new ApiError('Brifing alınamadı (HTTP 502).', 502));
  const { container } = render(<TodayScreen onNavigate={() => {}} />);
  await screen.findByRole('alert');

  const inner = container.querySelector('.page > .page__inner');
  expect(inner?.classList.contains('page__inner--app')).toBe(true);
});
