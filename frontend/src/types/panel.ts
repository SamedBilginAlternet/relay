/**
 * The wire shape of `GET /api/panel`. Every field is a count or a sum the database
 * produced — there is no model in this path, so there is nothing here that could be a
 * guess.
 */

/** Mirrors `RunStatus` on the server. The panel always receives every bucket. */
export type PanelRunStatus =
  | 'planning'
  | 'awaiting_approval'
  | 'running'
  | 'done'
  | 'failed'
  | 'cancelled';

export type PanelRuns = {
  total: number;
  /** Every status, zeros included — a missing key would read as "unknown", not as "none". */
  byStatus: Record<string, number>;
};

export type PanelApprovals = {
  /** Every step in the window. */
  steps: number;
  /** Steps that stopped for a human — decided or still waiting. */
  gated: number;
  /** `gated / steps`, 0..1. */
  gatedRatio: number;
  approved: number;
  rejected: number;
  /** Still standing at the gate. Not counted as a refusal. */
  pending: number;
  /** `approved / (approved + rejected)`, 0..1; 0 when nothing was decided. */
  approvalRate: number;
};

/** One refusal, with the sentence a human typed and the door back to the record. */
export type PanelRejection = {
  runId: string;
  stepId: string;
  runGoal: string | null;
  stepTitle: string | null;
  toolName: string | null;
  /** Null when somebody refused without writing anything. Still a refusal. */
  reason: string | null;
  at: string | null;
};

export type PanelToolUsage = {
  toolName: string;
  calls: number;
  tokens: number;
  costUsd: number;
};

export type PanelReport = {
  /** ISO-8601, inclusive. */
  from: string;
  /** ISO-8601, exclusive. */
  to: string;
  runs: PanelRuns;
  approvals: PanelApprovals;
  rejections: PanelRejection[];
  tools: PanelToolUsage[];
  totals: { tokens: number; costUsd: number };
};

/** Both bounds accept `2026-07-25` or a full instant; both are optional. */
export type PanelRange = { from?: string; to?: string };
