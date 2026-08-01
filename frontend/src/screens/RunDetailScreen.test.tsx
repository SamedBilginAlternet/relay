// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Run, RunEvent, Step } from '../types/api';
import type { RunStreamHandlers } from '../data/RunSource';

/**
 * Why this file exists.
 *
 * <p>Issue #72: a run parked at the approval gate could only be decided on the screen it
 * was started on. Leaving Sohbet stranded it — 32 runs had piled up on the live box with
 * nowhere left to answer them. The gate now lives on the Geçmiş detail screen as well, and
 * that is the only place in the product where a write can be authorised from a run somebody
 * else's session started.
 *
 * <p>Three claims are worth a test because each one failed live before it passed:
 *
 * <ul>
 *   <li>the parameters in the boxes are the parameters that get sent — approval is a
 *       signature, and it has to be against what was read;
 *   <li>the screen keeps following the run afterwards. It used to refetch once, catch the
 *       run mid-flight and freeze: on 2026-08-01 the run failed on the server at 04:18:48
 *       while this screen counted the step's duration up past 54 seconds;
 *   <li>a decision that never reached the server is not reported as a parameter refusal.
 *       A 502 from a redeploy was drawn under the input boxes, as if the values were wrong.
 * </ul>
 */

const RUN_ID = '11111111-2222-3333-4444-555555555555';
const STEP_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

function step(overrides: Partial<Step> = {}): Step {
  return {
    id: STEP_ID,
    ordinal: 1,
    title: 'Ekibe Slack’ten özet gönder',
    role: 'slack-agent',
    toolName: 'slack.postMessage',
    params: { text: 'Sürüm çıktı', channel: '#all-samed' },
    status: 'awaiting_approval',
    decision: null,
    pausedBy: 'policy',
    rejectReason: null,
    result: null,
    error: null,
    tokens: 0,
    costUsd: 0,
    startedAt: null,
    finishedAt: null,
    ...overrides,
  };
}

function run(overrides: Partial<Run> = {}): Run {
  return {
    id: RUN_ID,
    goal: 'Sürüm notunu ekibe duyur',
    status: 'awaiting_approval',
    costTokens: 974,
    costUsd: 0,
    budgetUsd: null,
    steps: [step()],
    messages: [],
    createdAt: new Date().toISOString(),
    finishedAt: null,
    ...overrides,
  };
}

const getRun = vi.fn<() => Promise<Run>>();
const approveStep = vi.fn<(runId: string, stepId: string, params?: Record<string, unknown>) => Promise<void>>();
const rejectStep = vi.fn<(runId: string, stepId: string, reason: string) => Promise<void>>();
/** Handlers of every stream the screen opened, so a test can push a frame down one. */
const streams: RunStreamHandlers[] = [];

vi.mock('../data', () => ({
  getRunSource: () => ({
    getRun,
    approveStep,
    rejectStep,
    streamRun: (_runId: string, handlers: RunStreamHandlers) => {
      streams.push(handlers);
      handlers.onStatus('live');
      return () => handlers.onStatus('closed');
    },
    cancelRun: vi.fn(),
    rerun: vi.fn(),
  }),
}));

// jsdom has no scroller; the chat panel pins itself to the newest message on every render.
Element.prototype.scrollTo ??= function scrollTo() {};

const { RunDetailScreen } = await import('./RunDetailScreen');
const { ApiError } = await import('../data/ApiRunSource');

function show() {
  return render(<RunDetailScreen runId={RUN_ID} onBack={() => {}} onNavigate={() => {}} />);
}

/** Push one SSE frame down every stream the screen has open. */
async function stream(event: RunEvent) {
  await act(async () => {
    for (const handlers of streams) handlers.onEvent(event);
  });
}

afterEach(() => {
  cleanup();
  streams.length = 0;
  getRun.mockReset();
  approveStep.mockReset();
  rejectStep.mockReset();
});

it('a_run_still_parked_at_the_gate_can_be_approved_from_the_history_screen', async () => {
  getRun.mockResolvedValue(run());
  approveStep.mockResolvedValue(undefined);
  show();

  fireEvent.click(await screen.findByRole('button', { name: 'Onayla' }));

  await waitFor(() => expect(approveStep).toHaveBeenCalledWith(RUN_ID, STEP_ID, undefined));
});

it('the_parameters_on_screen_are_the_ones_the_approval_sends', async () => {
  getRun.mockResolvedValue(run());
  approveStep.mockResolvedValue(undefined);
  show();

  const channel = (await screen.findByDisplayValue('#all-samed')) as HTMLInputElement;
  fireEvent.change(channel, { target: { value: '#dev' } });

  // The button says so out loud once a value has been rewritten.
  fireEvent.click(screen.getByRole('button', { name: 'Düzelt ve onayla' }));

  await waitFor(() =>
    expect(approveStep).toHaveBeenCalledWith(RUN_ID, STEP_ID, { channel: '#dev' }),
  );
});

it('the_reason_typed_at_the_gate_is_the_reason_that_is_sent', async () => {
  getRun.mockResolvedValue(run());
  rejectStep.mockResolvedValue(undefined);
  show();

  fireEvent.click(await screen.findByRole('button', { name: 'Reddet' }));
  fireEvent.change(screen.getByLabelText('Reddetme gerekçesi'), {
    target: { value: 'Yanlış kanal' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Gönder' }));

  await waitFor(() => expect(rejectStep).toHaveBeenCalledWith(RUN_ID, STEP_ID, 'Yanlış kanal'));
});

it('the_screen_follows_the_run_after_the_decision_instead_of_freezing', async () => {
  getRun.mockResolvedValue(run());
  approveStep.mockResolvedValue(undefined);
  show();

  fireEvent.click(await screen.findByRole('button', { name: 'Onayla' }));
  await waitFor(() => expect(approveStep).toHaveBeenCalled());

  // What the server does next arrives on the stream, not on a second click.
  await stream({
    type: 'step.finished',
    stepId: STEP_ID,
    status: 'failed',
    result: null,
    tokens: 120,
    costUsd: 0.0004,
    error: 'Issue type “Bug” bu projede yok.',
  });
  await stream({ type: 'run.finished', status: 'failed' });

  // `Durdur` only exists while the run can still be stopped: its going is the screen
  // saying out loud that it knows the run is over.
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Durdur' })).toBeNull());
  expect((await screen.findAllByText('Hata')).length).toBeGreaterThan(0);
  // The gate is gone with the step it belonged to — nothing is left to press twice.
  expect(screen.queryByRole('button', { name: 'Onayla' })).toBeNull();
});

it('a_run_that_is_already_over_opens_no_stream', async () => {
  getRun.mockResolvedValue(run({ status: 'done', steps: [step({ status: 'done', decision: 'approved' })] }));
  show();

  await screen.findAllByText('Tamamlandı');
  expect(streams).toHaveLength(0);
});

it('a_decision_that_never_reached_the_server_is_not_reported_as_a_bad_parameter', async () => {
  getRun.mockResolvedValue(run());
  approveStep.mockRejectedValue(
    new ApiError('Sunucu yanıt vermedi (HTTP 502). Backend ayakta mı?', 502),
  );
  show();

  fireEvent.click(await screen.findByRole('button', { name: 'Onayla' }));

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toContain('HTTP 502');
  // The gate survives the failure, so the same decision can be taken again.
  expect(screen.getByRole('button', { name: 'Onayla' })).toBeTruthy();
});

it('a_refused_parameter_edit_is_reported_under_the_box_that_caused_it', async () => {
  getRun.mockResolvedValue(run());
  approveStep.mockRejectedValue(
    new ApiError('Düzenlenen parametreler şemaya uymuyor — adım onayda kaldı.', 400, {
      channel: 'Kanal adı # ile başlamalı.',
    }),
  );
  show();

  const channel = (await screen.findByDisplayValue('#all-samed')) as HTMLInputElement;
  fireEvent.change(channel, { target: { value: 'dev' } });
  fireEvent.click(screen.getByRole('button', { name: 'Düzelt ve onayla' }));

  expect(await screen.findByText('Kanal adı # ile başlamalı.')).toBeTruthy();
  expect(screen.getByDisplayValue('dev').getAttribute('aria-invalid')).toBe('true');
});

/**
 * The transcript is the same component Sohbet uses, and Sohbet named its agents in Turkish
 * while this screen printed the backend's ids — `verifier → coordinator` in the middle of a
 * Turkish audit trail (#97, #75). Naming belongs to the transcript, so both screens read the
 * same way and neither store keeps a translated id.
 */
it('the_transcript_names_the_agents_in_the_language_the_screen_is_in', async () => {
  getRun.mockResolvedValue(
    run({
      messages: [
        {
          id: 'm-1',
          stepId: null,
          fromAgent: 'verifier',
          toAgent: 'coordinator',
          content: 'Adım doğrulandı.',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'm-2',
          stepId: null,
          fromAgent: 'jira-agent',
          toAgent: 'user',
          content: 'Kayıt açıldı.',
          createdAt: new Date().toISOString(),
        },
      ],
    }),
  );
  const { container } = show();

  await screen.findByText('Adım doğrulandı.');
  const text = container.textContent ?? '';
  expect(text).toContain('Doğrulayıcı');
  expect(text).toContain('Koordinatör');
  expect(text).toContain('Jira Uzmanı');
  expect(text).not.toMatch(/\bverifier\b|\bcoordinator\b|\bjira-agent\b/);
});
