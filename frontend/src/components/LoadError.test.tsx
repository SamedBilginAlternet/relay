// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { describeLoadError, LoadError } from './LoadError';

/**
 * Why this file exists.
 *
 * <p>With the API blocked, Panel and Politikalar showed one thing: "Failed to
 * fetch". English, in a Turkish product, with no explanation and nothing to
 * press — while three other screens described the same fault properly (#63).
 * The rule that replaced it is easy to break by accident, because the tempting
 * line of code is always `setError(err.message)`.
 *
 * <p>So these tests hold two promises. A person never reads the machine's own
 * words — not the browser's exception text, not an environment variable name —
 * and there is always a way to try again.
 */

afterEach(cleanup);

it('a_blocked_request_is_explained_in_turkish_not_as_failed_to_fetch', () => {
  const text = describeLoadError(new TypeError('Failed to fetch'));

  expect(text).toBe('Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.');
  expect(text).not.toMatch(/failed to fetch/i);
});

it('a_server_error_is_told_apart_from_an_unreachable_server', () => {
  const down = Object.assign(new Error('Internal Server Error'), { status: 500 });

  expect(describeLoadError(down)).toBe('Sunucu bu isteği yanıtlayamadı. Birazdan tekrar dene.');
  expect(describeLoadError(Object.assign(new Error('Unauthorized'), { status: 401 }))).toBe(
    'Oturumun düşmüş görünüyor. Tekrar giriş yapman gerekiyor.',
  );
});

it('english_from_a_proxy_or_a_framework_never_reaches_the_screen', () => {
  // Gateways answer in English; the screen around them is Turkish.
  const gateway = Object.assign(new Error('Bad Request: invalid payload'), { status: 400 });

  expect(describeLoadError(gateway)).toBe(
    'İstek sunucuya ulaştı ama kabul edilmedi. Tekrar dene.',
  );
  expect(describeLoadError(new SyntaxError('Unexpected token < in JSON at position 0'))).toBe(
    'Sunucudan beklenmeyen bir yanıt geldi. Tekrar dene.',
  );
});

it('no_message_shown_to_a_user_names_an_environment_variable', () => {
  // The real 5xx text from ApiRunSource, which a signed-in user cannot act on.
  const withEnv = Object.assign(
    new Error('Sunucu yanıt vermedi (HTTP 500). Backend ayakta mı? VITE_API_BASE_URL: /api'),
    { status: 500 },
  );

  expect(describeLoadError(withEnv)).not.toMatch(/VITE_/);
});

it('a_sentence_the_server_wrote_itself_survives', () => {
  const fromServer = Object.assign(new Error('Bu projeye erişimin yok.'), { status: 400 });

  expect(describeLoadError(fromServer)).toBe('Bu projeye erişimin yok.');
});

it('an_error_with_nothing_readable_in_it_still_says_something', () => {
  expect(describeLoadError(null)).toBe('Bu ekranın verisi yüklenemedi. Tekrar dene.');
  expect(describeLoadError({})).toBe('Bu ekranın verisi yüklenemedi. Tekrar dene.');
});

it('the_error_block_offers_a_way_to_try_again', () => {
  let retried = 0;
  render(<LoadError error={new TypeError('Failed to fetch')} onRetry={() => (retried += 1)} />);

  const alert = screen.getByRole('alert');
  expect(alert.textContent).toContain('Sunucuya ulaşılamadı');
  expect(alert.textContent).not.toMatch(/failed to fetch/i);

  screen.getByRole('button', { name: 'Tekrar dene' }).click();
  expect(retried).toBe(1);
});
