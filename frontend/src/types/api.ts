/**
 * Relay API types — mirrors docs/ARCHITECTURE.md §5 exactly.
 * This file is the single source of truth on the frontend side; do not
 * "improve" the shapes here without changing the contract in the doc.
 */

export type StepStatus =
  | 'pending'
  | 'awaiting_approval'
  | 'running'
  | 'done'
  | 'failed'
  | 'rejected';

export type StepDecision = 'auto' | 'approved' | 'rejected' | null;

/**
 * Which question a parked step is asking. Both pauses arrive as `awaiting_approval`, and a
 * screen that cannot tell them apart calls a spending limit a writing permission — which is
 * what it did, while the button behind it lifted the limit.
 */
export type PauseReason = 'policy' | 'budget' | null;

export type Step = {
  id: string;
  ordinal: number;
  title: string;
  role: string;
  toolName: string | null;
  params: Record<string, unknown>;
  status: StepStatus;
  decision: StepDecision;
  pausedBy: PauseReason;
  rejectReason: string | null;
  result: unknown | null;
  error: string | null;
  tokens: number;
  costUsd: number;
  /**
   * Which model answered this step, provider-qualified — `groq:llama-3.1-8b-instant`.
   *
   * <p>A step is rarely one call, and the tier is chosen per job, so the calls do not all
   * land on the same model. The backend sends the model of the call that did the most
   * tokens: what answered here, not what the whole step ran on.
   *
   * <p>Optional twice over. It is `null` on a step that made no model call, and absent
   * altogether from a server that predates the field. Both mean unknown, and unknown is
   * drawn as nothing — never as a placeholder, never as the cheap one.
   */
  model?: string | null;
  /**
   * What this step's tokens would have cost had every one of its calls been billed at the
   * strong model's price. The same measured token counts, the other price list — arithmetic,
   * not an estimate.
   *
   * <p>`null` when it cannot be derived (the offline stub counts characters and no provider
   * ever billed them), and absent from a server that predates the field. Neither is zero:
   * zero would be the claim that the strong model was free.
   */
  premiumCostUsd?: number | null;
  startedAt: string | null;
  finishedAt: string | null;
};

export type AgentMessage = {
  id: string;
  stepId: string | null;
  fromAgent: string;
  toAgent: string;
  content: string;
  createdAt: string;
};

export type Run = {
  id: string;
  goal: string;
  status: string;
  costTokens: number;
  costUsd: number;
  budgetUsd: number | null;
  steps: Step[];
  messages: AgentMessage[];
  createdAt: string;
  finishedAt: string | null;
};

/** Known values of `Run.status` (the wire type stays `string`). */
export type RunStatus =
  | 'planning'
  | 'awaiting_approval'
  | 'running'
  | 'done'
  | 'failed'
  | 'cancelled';

/** Row shape used by the history list (`GET /api/runs`). */
export type RunSummary = {
  id: string;
  goal: string;
  status: string;
  costTokens: number;
  costUsd: number;
  budgetUsd: number | null;
  createdAt: string;
  finishedAt: string | null;
  stepCount: number;
};

/* ------------------------------------------------------------------ */
/* SSE events — ARCHITECTURE.md §5 "SSE olay tipleri"                  */
/* ------------------------------------------------------------------ */

export type RunPlannedEvent = { type: 'run.planned'; steps: Step[] };
export type StepStartedEvent = { type: 'step.started'; stepId: string };
/**
 * The gate frame. It carries the parameters the specialist just finalised — the whole
 * point of the pause is that a person reads them before saying yes, so dropping them here
 * leaves the screen showing the planner's empty draft.
 */
export type StepAwaitingEvent = {
  type: 'step.awaiting';
  stepId: string;
  ordinal?: number;
  title?: string;
  toolName?: string | null;
  params?: Record<string, unknown> | null;
  reason?: string | null;
  pausedBy?: PauseReason;
};
export type StepFinishedEvent = {
  type: 'step.finished';
  stepId: string;
  status: StepStatus;
  result: unknown | null;
  tokens: number;
  costUsd: number;
  error?: string | null;
  rejectReason?: string | null;
  decision?: StepDecision;
  /**
   * The server's own copy of the step, timestamps included. The reducer prefers these over
   * the browser clock: the stream replays, and a replayed frame that re-dates a step erases
   * the duration the trail is supposed to prove.
   */
  step?: Step;
};
export type AgentMessageEvent = {
  type: 'agent.message';
  id?: string;
  from?: string; fromAgent?: string;
  to?: string; toAgent?: string;
  content: string;
  stepId?: string | null;
  createdAt?: string;
};
export type RunCostEvent = { type: 'run.cost'; tokens: number; costUsd: number };
export type RunFinishedEvent = {
  type: 'run.finished';
  status: string;
  /** When it ended, by the clock that ended it — not by the clock that heard about it. */
  finishedAt?: string | null;
};

export type RunEvent =
  | RunPlannedEvent
  | StepStartedEvent
  | StepAwaitingEvent
  | StepFinishedEvent
  | AgentMessageEvent
  | RunCostEvent
  | RunFinishedEvent;

export const RUN_EVENT_TYPES = [
  'run.planned',
  'step.started',
  'step.awaiting',
  'step.finished',
  'agent.message',
  'run.cost',
  'run.finished',
] as const;

/* ------------------------------------------------------------------ */
/* Connections & policies                                              */
/* ------------------------------------------------------------------ */

export type Provider = 'jira' | 'slack' | 'github' | 'google';

/**
 * Google is the one provider with no token to paste: the user is sent to Google's
 * consent screen and the refresh token lands in the connection server-side.
 *
 * @property configured whether the server has the client id/secret at all
 * @property connected  whether a user has already granted access
 */
export type GoogleStatus = {
  configured: boolean;
  connected: boolean;
  scopes: string;
  redirectUri: string;
  startUrl: string;
};

/** Values come back masked from the API (`xoxb-****1234`). */
export type Connection = {
  provider: Provider;
  configured: boolean;
  config: Record<string, string>;
  updatedAt: string | null;
};

export type ConnectionTestResult = {
  ok: boolean;
  message: string;
  checkedAt: string;
};

export type ToolPolicyMode = 'auto' | 'ask' | 'forbidden';

export type ToolPolicy = {
  provider: Provider;
  toolName: string;
  mode: ToolPolicyMode;
};

export type Health = {
  status: string;
  version: string;
  llm: string;
};
