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
   * After a dropped connection the source refetches the full run and hands it back.
   *
   * <p>The stream itself replays — the server keeps a backlog and gives every new subscriber
   * the story from the start — but only the last 400 frames of it, so this is what closes a
   * longer hole. It arrives late by definition: the socket goes on talking while the request
   * is in flight, so the consumer must merge it rather than assign it.
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
  /**
   * Stops the flow. Answers with the run as it stands: already `cancelled` when it was
   * waiting on a human, still `running` when a tool call is in flight — that call is
   * allowed to finish, and `run.finished` lands when it does.
   */
  cancelRun(runId: string): Promise<Run>;

  streamRun(runId: string, handlers: RunStreamHandlers): Unsubscribe;

  /**
   * Approves the step. `params` carries only the fields the user corrected on screen —
   * omit it to approve exactly what is shown, which is what approval always did.
   * An edit the tool's schema refuses throws with `fields` filled in and changes nothing.
   */
  approveStep(runId: string, stepId: string, params?: Record<string, unknown>): Promise<void>;
  rejectStep(runId: string, stepId: string, reason: string): Promise<void>;

  getConnections(): Promise<Connection[]>;
  saveConnection(provider: Provider, config: Record<string, string>): Promise<Connection>;
  testConnection(provider: Provider): Promise<ConnectionTestResult>;
  /** Google has no token field — the screen needs to know whether to offer the consent flow. */
  getGoogleStatus(): Promise<GoogleStatus>;
}
