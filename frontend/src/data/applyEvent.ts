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
        })),
      };

    case 'step.awaiting':
      return {
        ...run,
        status: 'awaiting_approval',
        steps: patchStep(run.steps, event.stepId, (s) => ({
          ...s,
          status: 'awaiting_approval',
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
        fromAgent: event.from,
        toAgent: event.to,
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
