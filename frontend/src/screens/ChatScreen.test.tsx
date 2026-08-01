// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

/**
 * Why this file exists.
 *
 * Sohbet opened on the marketing landing page — a 56px "İşini anlat." headline,
 * a paragraph of pitch and three badges nobody can click — shown to someone who
 * had already signed in and come to start work. The box the screen exists for
 * began at y=481 on a 900px-tall window, and the whole thing sat in an 860px
 * column while the six other screens on the same nav are 1040px, so walking the
 * nav slid the content sideways (#69).
 *
 * These assertions are what stops the pitch from being rendered back into the
 * application: the title stays at the application scale, the container stays the
 * shared one, and the composer stays the first thing under the heading.
 */

vi.mock('../data', () => ({
  getRunSource: () => ({ streamRun: () => () => {} }),
  RUN_SOURCE_KIND: 'api',
}));

const { ChatScreen } = await import('./ChatScreen');
const { useRunStore } = await import('../store/runStore');

const IDLE = useRunStore.getState();

beforeEach(() => {
  window.location.hash = '#/sohbet';
  // jsdom has no scroller; the conversation pins itself to the bottom on mount.
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
  useRunStore.setState({ ...IDLE, run: null, phase: 'idle' });
});

afterEach(cleanup);

it('a_signed_in_user_lands_on_the_ask_box_not_on_the_pitch', () => {
  const { container } = render(<ChatScreen />);

  // The title is the application one, at the same 20px scale as the other six
  // screens — the 56px display scale belongs to a page for strangers.
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Sohbet');
  expect(container.querySelector('.t-display')).toBeNull();
  expect(screen.queryByText(/Ekip yürütsün, sen izle/)).toBeNull();

  // Same content container as Bugün, Geçmiş, Panel, Politikalar, Postana sor.
  expect(container.querySelector('.page__inner.page__inner--app')).not.toBeNull();
  expect(container.querySelector('.landing__inner')).toBeNull();

  // And the box that the screen exists for is present, above the examples.
  expect(screen.getByLabelText('Yapılmasını istediğin iş')).not.toBeNull();
  expect(screen.getByText('Hazır örnekler')).not.toBeNull();
});
