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
  /** A human refused this step. Steps closed by stopping a run are not in here. */
  rejected: number;
  /**
   * Steps written off because somebody stopped the whole run. Pressing Durdur is one
   * decision about a run, not N decisions about its steps — so these are shown on their
   * own and kept out of `rejected` and out of `approvalRate`.
   */
  cancelled: number;
  /** Still standing at the gate. Not counted as a refusal. */
  pending: number;
  /**
   * `approved / (approved + rejected)`, 0..1; 0 when nothing was decided.
   *
   * Taking the cancellations out of the denominator moves this number *up*. That is the
   * honest direction and the uncomfortable one, which is why it is still on the screen:
   * a judge who asks about it should get the real answer, not a missing card.
   */
  approvalRate: number;
};

/** One refusal, with the sentence a human typed and the door back to the record. */
export type PanelRejection = {
  runId: string;
  stepId: string;
  runGoal: string | null;
  /**
   * Status of the run this line belongs to. A step refused by a person on a run that was
   * cancelled later is still a refusal and stays in the refusal list — the status says so
   * on the line rather than moving it somewhere else.
   */
  runStatus: string | null;
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
  /** Only what a human turned down. This is the list the approval gate is judged on. */
  rejections: PanelRejection[];
  /** Steps closed by stopping a run. Same shape, different question. */
  cancellations: PanelRejection[];
  tools: PanelToolUsage[];
  totals: { tokens: number; costUsd: number };
};

/** Both bounds accept `2026-07-25` or a full instant; both are optional. */
export type PanelRange = { from?: string; to?: string };
