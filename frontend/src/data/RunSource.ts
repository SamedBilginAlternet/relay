import type {
  Connection,
  ConnectionTestResult,
  GoogleStatus,
  Health,
  Provider,
  Run,
  RunEvent,
  RunSummary,
} from '../types/api';

export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'closed';

export type RunStreamHandlers = {
  onEvent: (event: RunEvent) => void;
  onStatus: (status: StreamStatus) => void;
  /**
   * SSE has no replay. After a dropped connection the source refetches the
   * full run and hands it back so the UI can fill the gap.
   */
  onResync?: (run: Run) => void;
};

export type Unsubscribe = () => void;

/**
 * Everything the UI is allowed to know about the backend.
 * Components never call `fetch` — they go through a RunSource.
 */
export interface RunSource {
  readonly kind: 'api' | 'mock';

  health(): Promise<Health>;

  createRun(goal: string, budgetUsd?: number | null): Promise<{ runId: string }>;
  getRun(runId: string): Promise<Run>;
  listRuns(): Promise<RunSummary[]>;
  rerun(runId: string): Promise<{ runId: string }>;

  streamRun(runId: string, handlers: RunStreamHandlers): Unsubscribe;

  approveStep(runId: string, stepId: string): Promise<void>;
  rejectStep(runId: string, stepId: string, reason: string): Promise<void>;

  getConnections(): Promise<Connection[]>;
  saveConnection(provider: Provider, config: Record<string, string>): Promise<Connection>;
  testConnection(provider: Provider): Promise<ConnectionTestResult>;
  /** Google has no token field — the screen needs to know whether to offer the consent flow. */
  getGoogleStatus(): Promise<GoogleStatus>;
}
