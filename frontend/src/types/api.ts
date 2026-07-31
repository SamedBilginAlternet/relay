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

export type Step = {
  id: string;
  ordinal: number;
  title: string;
  role: string;
  toolName: string | null;
  params: Record<string, unknown>;
  status: StepStatus;
  decision: StepDecision;
  rejectReason: string | null;
  result: unknown | null;
  error: string | null;
  tokens: number;
  costUsd: number;
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
export type StepAwaitingEvent = { type: 'step.awaiting'; stepId: string };
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
export type RunFinishedEvent = { type: 'run.finished'; status: string };

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

export type Provider = 'jira' | 'slack';

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
