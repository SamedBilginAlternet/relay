import type { AgentMessage, Run, RunEvent, Step, StepStatus } from '../types/api';

let messageSeq = 0;
const nextMessageId = () => `msg-local-${Date.now().toString(36)}-${messageSeq++}`;

/** A run that has said its last word. */
export function isTerminal(status: string): boolean {
  return status === 'done' || status === 'failed' || status === 'cancelled';
}

/**
 * How far a step has got. Only ever used to compare, never shown.
 *
 * <p>The backend replays a run's whole backlog to every new subscriber, so the same frame
 * can arrive twice — and the second time it is a description of the past. Ranking the
 * states is how the reducer refuses to move a step backwards on a replay.
 */
const PROGRESS: Record<StepStatus, number> = {
  pending: 0,
  awaiting_approval: 1,
  running: 2,
  done: 3,
  failed: 3,
  rejected: 3,
};

/** Everything about a step that is history rather than description. */
function progress(step: Step) {
  return {
    status: step.status,
    decision: step.decision,
    pausedBy: step.pausedBy,
    rejectReason: step.rejectReason,
    result: step.result,
    error: step.error,
    tokens: step.tokens,
    costUsd: step.costUsd,
    startedAt: step.startedAt,
    finishedAt: step.finishedAt,
  };
}

/**
 * Pure reducer: run state + SSE event -> new run state.
 * Shared by the zustand store and the mock source so both stay consistent.
 *
 * <p>Applying the same event twice has to be the same as applying it once. The stream is not
 * a one-shot feed: the backend replays up to four hundred past events to every subscriber,
 * deliberately, so a client that arrives late still gets the story. The reducer used to
 * assume the opposite and stamp `Date.now()` on whatever arrived — so one dropped connection
 * rewrote every step's start and finish to the moment of the reconnect and the durations on
 * screen collapsed to zero. Timestamps therefore come from the server's own frame, and
 * nothing here ever moves a step backwards.
 */
export function applyEvent(run: Run, event: RunEvent, now: string = new Date().toISOString()): Run {
  switch (event.type) {
    case 'run.planned': {
      // The plan frame says what the work is; it does not say what has happened to it. It is
      // published when the run is planned and again when the coordinator repairs the plan, so
      // a replayed one carries the shape of the run's first second — and taking its steps
      // wholesale is what wiped every startedAt back to null.
      const live = new Map(run.steps.map((s) => [s.id, s]));
      const steps = [...event.steps]
        .sort((a, b) => a.ordinal - b.ordinal)
        .map((planned) => {
          const known = live.get(planned.id);
          return known && PROGRESS[known.status] >= PROGRESS[planned.status]
            ? { ...planned, ...progress(known) }
            : planned;
        });
      return { ...run, steps, status: run.status === 'planning' ? 'running' : run.status };
    }

    case 'step.started':
      return {
        ...run,
        status: isTerminal(run.status) ? run.status : 'running',
        steps: patchStep(run.steps, event.stepId, (s) =>
          PROGRESS[s.status] > PROGRESS.running
            ? s
            : {
                ...s,
                status: 'running',
                startedAt: s.startedAt ?? now,
                error: null,
                // Whatever it was waiting for, it is not waiting any more.
                pausedBy: null,
              },
        ),
      };

    case 'step.awaiting':
      return {
        ...run,
        status: isTerminal(run.status) ? run.status : 'awaiting_approval',
        steps: patchStep(run.steps, event.stepId, (s) =>
          PROGRESS[s.status] > PROGRESS.awaiting_approval
            ? s
            : {
                ...s,
                status: 'awaiting_approval',
                // The backend finalises the call before parking, precisely so the human reads
                // what will be sent. Keeping only the id here put the planner's draft on the
                // approval screen — an empty channel and an empty message, approved blind.
                title: event.title ?? s.title,
                toolName: event.toolName ?? s.toolName,
                params: event.params ?? s.params,
                // Which of the two gates this is. Without it the screen offered the same
                // sentence either way, so a run stopped by its budget read as a write it had
                // to permit.
                pausedBy: event.pausedBy ?? s.pausedBy ?? null,
              },
        ),
      };

    case 'step.finished':
      return {
        ...run,
        status: run.status === 'awaiting_approval' ? 'running' : run.status,
        steps: patchStep(run.steps, event.stepId, (s) => ({
          ...s,
          status: event.status,
          result: event.result ?? null,
          error: event.error ?? null,
          decision: event.decision ?? s.decision,
          pausedBy: null,
          rejectReason: event.rejectReason ?? s.rejectReason,
          tokens: event.tokens ?? s.tokens,
          costUsd: event.costUsd ?? s.costUsd,
          // The frame carries the server's own copy of the step. Preferring it over the
          // browser clock is what makes a replay harmless: the second application writes
          // the same two timestamps as the first.
          startedAt: event.step?.startedAt ?? s.startedAt ?? now,
          finishedAt: event.step?.finishedAt ?? s.finishedAt ?? now,
        })),
      };

    case 'agent.message': {
      const id = event.id ?? nextMessageId();
      if (run.messages.some((m) => m.id === id)) return run;
      const message: AgentMessage = {
        id,
        stepId: event.stepId ?? null,
        // Sozlesme `from/to` der; backend tam entity (`fromAgent/toAgent`)
        // yayinliyor. Ikisini de kabul et — bir daha bu yuzden bos ekran olmasin.
        fromAgent: event.fromAgent ?? event.from ?? 'unknown',
        toAgent: event.toAgent ?? event.to ?? 'unknown',
        content: event.content,
        createdAt: event.createdAt ?? now,
      };
      return { ...run, messages: [...run.messages, message] };
    }

    case 'run.cost':
      return { ...run, costTokens: event.tokens, costUsd: event.costUsd };

    case 'run.finished':
      return {
        ...run,
        status: event.status,
        finishedAt: event.finishedAt ?? run.finishedAt ?? now,
      };

    default:
      return run;
  }
}

/**
 * Folds a freshly fetched run into what the stream has already told us.
 *
 * <p>The gap fill after a reconnect is a request in flight, and the socket keeps talking
 * while it is: a `step.finished` that landed in between used to be silently undone when the
 * older snapshot replaced the whole run. Whichever side has got further wins, per step.
 */
export function mergeRun(local: Run, fresh: Run): Run {
  const live = new Map(local.steps.map((s) => [s.id, s]));
  const steps = fresh.steps.map((s) => {
    const known = live.get(s.id);
    return known && PROGRESS[known.status] > PROGRESS[s.status] ? { ...s, ...progress(known) } : s;
  });
  const seen = new Set(fresh.messages.map((m) => m.id));
  return {
    ...fresh,
    status: isTerminal(local.status) && !isTerminal(fresh.status) ? local.status : fresh.status,
    finishedAt: fresh.finishedAt ?? local.finishedAt,
    steps,
    messages: [...fresh.messages, ...local.messages.filter((m) => !seen.has(m.id))],
  };
}

function patchStep(steps: Step[], stepId: string, fn: (step: Step) => Step): Step[] {
  return steps.map((s) => (s.id === stepId ? fn(s) : s));
}
