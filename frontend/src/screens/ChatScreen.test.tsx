// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Run, RunSummary } from '../types/api';

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
 *
 * <p>Then the screen learned to switch between runs (#125), and the two effects
 * that keep the address bar and the store in step turned out to disagree by one
 * render — the older run's id overwrote the one the user had just asked for.
 *
 * <p>The rail of other live flows has since moved into the sidebar (#130). What is
 * asserted here now is the other side of that move: this screen is about one flow, and a
 * bare `#/sohbet` is a request for a new one rather than for the last one.
 */

const getRun = vi.fn<(runId: string) => Promise<Run>>();
const listRuns = vi.fn<(o?: { status?: string; size?: number }) => Promise<RunSummary[]>>();

vi.mock('../data', () => ({
  getRunSource: () => ({ streamRun: () => () => {}, getRun, listRuns }),
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

/** A run with an id of its own — the switching tests need two of them. */
function runWithId(id: string): Run {
  return { ...run([]), id, goal: `${id} hedefi` };
}

function parked(id: string, goal: string): RunSummary {
  return {
    id,
    goal,
    status: 'awaiting_approval',
    costTokens: 0,
    costUsd: 0,
    budgetUsd: null,
    createdAt: '2026-08-01T08:00:00Z',
    finishedAt: null,
    stepCount: 4,
  };
}

beforeEach(() => {
  getRun.mockReset();
  listRuns.mockReset();
  listRuns.mockResolvedValue([]);
  getRun.mockImplementation(async (runId: string) => runWithId(runId));
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

it('the_address_bar_does_not_undo_a_move_to_another_run', async () => {
  // A run is on screen and the hash names it — the settled state.
  window.location.hash = '#/sohbet/r-a';
  useRunStore.setState({ ...IDLE, phase: 'ready', run: runWithId('r-a') });
  render(<ChatScreen />);
  await waitFor(() => expect(window.location.hash).toBe('#/sohbet/r-a'));

  // Something asks for a different run — a rail row, a link, the back button.
  await act(async () => {
    window.location.hash = '#/sohbet/r-b';
    await Promise.resolve();
  });

  // The store follows the address; the address does not roll back to the run that
  // happened to be loaded when the click landed.
  await waitFor(() => expect(useRunStore.getState().run?.id).toBe('r-b'));
  expect(window.location.hash).toBe('#/sohbet/r-b');

  // And it is fetched once. Rolling the address back sent the screen to `r-a` and
  // straight out again: two more requests, and the old run flashed up in between.
  expect(getRun.mock.calls.map(([id]) => id)).toEqual(['r-b']);
});

it('a_run_the_store_just_created_still_writes_itself_into_the_address', async () => {
  // `rerun` replaces the run under a hash that still names the old one. Nothing else
  // can publish the new id, so this effect has to — the guard above must not silence it.
  window.location.hash = '#/sohbet/r-a';
  useRunStore.setState({ ...IDLE, phase: 'ready', run: runWithId('r-a') });
  const view = render(<ChatScreen />);
  await waitFor(() => expect(window.location.hash).toBe('#/sohbet/r-a'));

  act(() => {
    useRunStore.setState({ run: runWithId('r-c') });
  });
  view.rerender(<ChatScreen />);

  await waitFor(() => expect(window.location.hash).toBe('#/sohbet/r-c'));
});

it('yeni_is_gives_an_empty_box_even_with_a_run_still_in_the_store', async () => {
  // `+ Yeni iş` is the sidebar's one primary action (#130) and it lands on a bare
  // `#/sohbet`. Before this, "new" meant whatever run was still in memory: the button
  // that starts work reopened the last flow, and the only route to a blank box was a
  // page reload.
  window.location.hash = '#/sohbet/r-a';
  useRunStore.setState({ ...IDLE, phase: 'ready', run: runWithId('r-a') });
  const view = render(<ChatScreen />);
  await waitFor(() => expect(window.location.hash).toBe('#/sohbet/r-a'));

  await act(async () => {
    window.location.hash = '#/sohbet';
    await Promise.resolve();
  });
  view.rerender(<ChatScreen />);

  expect(screen.getByLabelText('Yapılmasını istediğin iş')).not.toBeNull();
  // And the screen does not put the old run's id back in the address behind the user's
  // back — that is the same one-render disagreement #125 was fixed for.
  expect(window.location.hash).toBe('#/sohbet');
});

it('the_conversation_keeps_the_whole_width_now_that_the_rail_is_not_a_column', async () => {
  window.location.hash = '#/sohbet/r-a';
  useRunStore.setState({ ...IDLE, phase: 'ready', run: runWithId('r-a') });
  listRuns.mockImplementation(async (options) =>
    options?.status === 'awaiting_approval' ? [parked('r-b', 'Kararını bekleyen öteki iş')] : [],
  );

  const { container } = render(<ChatScreen />);

  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).not.toBeNull());
  // The other live flows are in the sidebar now (#130), where they are on screen from
  // Bugün and Panel as well. This screen no longer spends a column of the conversation on
  // them — and no longer asks the server for them either.
  expect(container.querySelector('.rail')).toBeNull();
  expect(container.querySelector('.workbench--railed')).toBeNull();
  expect(container.querySelector('.workbench')).not.toBeNull();
  expect(listRuns).not.toHaveBeenCalled();
});
