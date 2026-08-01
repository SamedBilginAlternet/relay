// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Run } from '../types/api';

/**
 * Why this file exists.
 *
 * Two things were wrong with Sohbet at the same time, and both were the kind a
 * unit test cannot see because they are about what ends up on the screen.
 *
 * <p>The screen opened on the marketing landing page — a 56px "İşini anlat."
 * headline, a paragraph of pitch and three badges nobody can click — shown to
 * someone who had already signed in and come to start work, in an 860px column
 * while the six other screens on the same nav sit at 1040px (#69).
 *
 * <p>And the conversation named its agents in whichever language the source
 * spoke: Turkish under the mock, `coordinator → jira-agent` under a live run —
 * so the demo looked finished and the product did not (#97).
 */

vi.mock('../data', () => ({
  getRunSource: () => ({ streamRun: () => () => {} }),
  RUN_SOURCE_KIND: 'api',
}));

const { ChatScreen } = await import('./ChatScreen');
const { useRunStore } = await import('../store/runStore');

const IDLE = useRunStore.getState();

function run(messages: Run['messages']): Run {
  return {
    id: 'r-1',
    goal: 'Bugünkü maillerime bak',
    status: 'running',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    steps: [],
    messages,
    createdAt: '2026-08-01T09:00:00Z',
    finishedAt: null,
  };
}

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

it('a_live_run_shows_agent_names_not_backend_role_ids', () => {
  useRunStore.setState({
    ...IDLE,
    phase: 'ready',
    run: run([
      {
        id: 'm-1',
        stepId: null,
        fromAgent: 'coordinator',
        toAgent: 'jira-agent',
        content: 'Kaydı aç.',
        createdAt: '2026-08-01T09:00:01Z',
      },
      {
        id: 'm-2',
        stepId: null,
        fromAgent: 'verifier',
        toAgent: 'coordinator',
        content: 'Alanlar isteneni karşılıyor.',
        createdAt: '2026-08-01T09:00:02Z',
      },
    ]),
  });

  const { container } = render(<ChatScreen />);

  expect(screen.getAllByText('Koordinatör').length).toBe(2);
  expect(screen.getByText('Jira Uzmanı')).not.toBeNull();
  expect(screen.getByText('Doğrulayıcı')).not.toBeNull();
  for (const id of ['coordinator', 'jira-agent', 'verifier']) {
    expect(container.textContent).not.toContain(id);
  }
});
