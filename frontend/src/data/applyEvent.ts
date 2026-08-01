import type { AgentMessage, Run, RunEvent, Step } from '../types/api';

let messageSeq = 0;
const nextMessageId = () => `msg-local-${Date.now().toString(36)}-${messageSeq++}`;

/**
 * Pure reducer: run state + SSE event -> new run state.
 * Shared by the zustand store and the mock source so both stay consistent.
 */
export function applyEvent(run: Run, event: RunEvent, now: string = new Date().toISOString()): Run {
  switch (event.type) {
    case 'run.planned': {
      const steps = [...event.steps].sort((a, b) => a.ordinal - b.ordinal);
      return { ...run, steps, status: run.status === 'planning' ? 'running' : run.status };
    }

    case 'step.started':
      return {
        ...run,
        status: 'running',
        steps: patchStep(run.steps, event.stepId, (s) => ({
          ...s,
          status: 'running',
          startedAt: s.startedAt ?? now,
          error: null,
          // Whatever it was waiting for, it is not waiting any more.
          pausedBy: null,
        })),
      };

    case 'step.awaiting':
      return {
        ...run,
        status: 'awaiting_approval',
        steps: patchStep(run.steps, event.stepId, (s) => ({
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
        })),
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
          rejectReason: event.rejectReason ?? s.rejectReason,
          tokens: event.tokens ?? s.tokens,
          costUsd: event.costUsd ?? s.costUsd,
          startedAt: s.startedAt ?? now,
          finishedAt: now,
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
      return { ...run, status: event.status, finishedAt: now };

    default:
      return run;
  }
}

function patchStep(steps: Step[], stepId: string, fn: (step: Step) => Step): Step[] {
  return steps.map((s) => (s.id === stepId ? fn(s) : s));
}
