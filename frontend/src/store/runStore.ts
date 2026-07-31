import { create } from 'zustand';
import { getRunSource } from '../data';
import { applyEvent } from '../data/applyEvent';
import type { StreamStatus, Unsubscribe } from '../data/RunSource';
import type { Run } from '../types/api';

export type RunPhase = 'idle' | 'creating' | 'loading' | 'ready' | 'error';

type RunState = {
  run: Run | null;
  phase: RunPhase;
  error: string | null;
  streamStatus: StreamStatus | 'idle';
  expandedStepId: string | null;
  rejectingStepId: string | null;
  busyStepId: string | null;
  lastGoal: string;
  /** Mobile: is the workflow bottom sheet open? */
  sheetOpen: boolean;

  startRun: (goal: string) => Promise<void>;
  openRun: (runId: string) => Promise<void>;
  retry: () => Promise<void>;
  rerun: () => Promise<void>;
  approve: (stepId: string) => Promise<void>;
  reject: (stepId: string, reason: string) => Promise<void>;
  toggleStep: (stepId: string) => void;
  setRejecting: (stepId: string | null) => void;
  setSheetOpen: (open: boolean) => void;
  closeRun: () => void;
};

let unsubscribe: Unsubscribe | null = null;

function stopStream(): void {
  unsubscribe?.();
  unsubscribe = null;
}

function errorText(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  return 'Beklenmeyen bir hata oldu.';
}

export const useRunStore = create<RunState>((set, get) => {
  const attachStream = (runId: string): void => {
    stopStream();
    unsubscribe = getRunSource().streamRun(runId, {
      onStatus: (status: StreamStatus) => set({ streamStatus: status }),
      onEvent: (event) => {
        const current = get().run;
        if (!current || current.id !== runId) return;
        set({ run: applyEvent(current, event) });
      },
      onResync: (fresh) => {
        // SSE has no replay — this is the gap fill after a reconnect.
        if (get().run?.id === fresh.id) set({ run: fresh });
      },
    });
  };

  const load = async (runId: string, opts: { stream: boolean }): Promise<void> => {
    try {
      const run = await getRunSource().getRun(runId);
      set({ run, phase: 'ready', error: null });
      if (opts.stream && !isTerminal(run.status)) attachStream(runId);
    } catch (err) {
      set({ phase: 'error', error: errorText(err) });
    }
  };

  return {
    run: null,
    phase: 'idle',
    error: null,
    streamStatus: 'idle',
    expandedStepId: null,
    rejectingStepId: null,
    busyStepId: null,
    lastGoal: '',
    sheetOpen: false,

    async startRun(goal: string) {
      const trimmed = goal.trim();
      if (!trimmed) return;
      stopStream();
      set({
        phase: 'creating',
        error: null,
        lastGoal: trimmed,
        expandedStepId: null,
        rejectingStepId: null,
        streamStatus: 'idle',
        run: null,
      });
      try {
        const { runId } = await getRunSource().createRun(trimmed);
        // Optimistic skeleton so the screen is never blank while planning.
        set({
          phase: 'ready',
          run: {
            id: runId,
            goal: trimmed,
            status: 'planning',
            costTokens: 0,
            costUsd: 0,
            budgetUsd: null,
            steps: [],
            messages: [],
            createdAt: new Date().toISOString(),
            finishedAt: null,
          },
        });
        attachStream(runId);
        // Fill in whatever the server already knows (budget, early steps).
        try {
          const fresh = await getRunSource().getRun(runId);
          const local = get().run;
          if (local && local.id === fresh.id && local.steps.length === 0) {
            set({ run: { ...fresh, messages: mergeMessages(fresh, local) } });
          }
        } catch {
          /* the stream is the primary path; a failed prefetch is not fatal */
        }
      } catch (err) {
        set({ phase: 'error', error: errorText(err) });
      }
    },

    async openRun(runId: string) {
      stopStream();
      set({
        phase: 'loading',
        error: null,
        run: null,
        expandedStepId: null,
        rejectingStepId: null,
        streamStatus: 'idle',
      });
      await load(runId, { stream: true });
    },

    async retry() {
      const runId = get().run?.id;
      if (runId) {
        set({ phase: 'loading', error: null });
        await load(runId, { stream: true });
        return;
      }
      const goal = get().lastGoal;
      if (goal) await get().startRun(goal);
    },

    async rerun() {
      const runId = get().run?.id;
      if (!runId) return;
      const goal = get().run?.goal ?? get().lastGoal;
      try {
        stopStream();
        set({ phase: 'creating', error: null, run: null, streamStatus: 'idle' });
        const res = await getRunSource().rerun(runId);
        set({
          phase: 'ready',
          run: {
            id: res.runId,
            goal,
            status: 'planning',
            costTokens: 0,
            costUsd: 0,
            budgetUsd: null,
            steps: [],
            messages: [],
            createdAt: new Date().toISOString(),
            finishedAt: null,
          },
        });
        attachStream(res.runId);
      } catch (err) {
        set({ phase: 'error', error: errorText(err) });
      }
    },

    async approve(stepId: string) {
      const runId = get().run?.id;
      if (!runId) return;
      set({ busyStepId: stepId });
      try {
        await getRunSource().approveStep(runId, stepId);
        set({ rejectingStepId: null });
      } catch (err) {
        set({ error: errorText(err) });
      } finally {
        set({ busyStepId: null });
      }
    },

    async reject(stepId: string, reason: string) {
      const runId = get().run?.id;
      if (!runId) return;
      set({ busyStepId: stepId });
      try {
        await getRunSource().rejectStep(runId, stepId, reason.trim() || 'Gerekçe belirtilmedi');
        set({ rejectingStepId: null });
      } catch (err) {
        set({ error: errorText(err) });
      } finally {
        set({ busyStepId: null });
      }
    },

    toggleStep(stepId: string) {
      set({ expandedStepId: get().expandedStepId === stepId ? null : stepId });
    },

    setRejecting(stepId: string | null) {
      set({ rejectingStepId: stepId });
    },

    setSheetOpen(open: boolean) {
      set({ sheetOpen: open });
    },

    closeRun() {
      stopStream();
      set({
        run: null,
        phase: 'idle',
        error: null,
        streamStatus: 'idle',
        expandedStepId: null,
        rejectingStepId: null,
        sheetOpen: false,
      });
    },
  };
});

export function isTerminal(status: string): boolean {
  return status === 'done' || status === 'failed' || status === 'cancelled';
}

function mergeMessages(fresh: Run, local: Run) {
  const seen = new Set(fresh.messages.map((m) => m.id));
  return [...fresh.messages, ...local.messages.filter((m) => !seen.has(m.id))];
}
