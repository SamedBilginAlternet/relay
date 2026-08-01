import type { PanelApprovals } from '../types/panel';

/**
 * The approval gate, read as a funnel.
 *
 * <p>WHY THIS IS A FUNNEL AND NOT FIVE BARS. `approvals` is the one part of the panel
 * whose fields are nested rather than parallel: every step that was gated is a step, every
 * decided step was gated, every approval was a decision. Drawing them as five bars of
 * equal footing — which is what the screen did — throws that away and lets a reader put
 * 71 approvals next to 394 steps as if the two numbers were about the same population.
 *
 * <p>It is also the single best picture of what this product claims: how much was
 * proposed, how much a human was asked about, and how much of that they said yes to.
 *
 * <p>THE ARITHMETIC IS THE POINT. A funnel that does not add up is worse than a table,
 * because the shape asserts a containment the numbers may not have. So the five terminal
 * buckets are checked against `gated` here rather than assumed, and the remainder is
 * carried out as `unaccounted` for the screen to print. Nothing is rescaled to make the
 * bar reach the end: a server that sends buckets which do not sum to `gated` gets a short
 * bar and a sentence saying by how much, not a picture that silently agrees with itself.
 */

export type FunnelStage = {
  key: string;
  label: string;
  value: number;
  /** Width to draw at, 0..1, against the widest stage. Clamped; `value` is never clamped. */
  share: number;
  /** `value / previous.value`, 0..1 — null on the first stage, which has no previous. */
  ofPrevious: number | null;
};

export type FunnelSlice = {
  key: string;
  label: string;
  value: number;
  /** Share of `gated`, 0..1. */
  share: number;
  /** A CSS custom property name from the panel palette — never a literal colour. */
  color: string;
};

export type ApprovalFunnel = {
  stages: FunnelStage[];
  /** How the gated steps ended. Sums to `gated` unless `unaccounted` says otherwise. */
  slices: FunnelSlice[];
  gated: number;
  /**
   * `gated - (approvedAsIs + approvedWithEdit + rejected + cancelled + pending)`.
   *
   * <p>Signed and usually zero. Positive means the gate holds steps no bucket claims;
   * negative means the buckets claim more steps than ever reached the gate. Either way it
   * is a fact about the data and it goes on the screen — the alternative is a chart that
   * looks complete because the code made it complete.
   */
  unaccounted: number;
};

function ratio(part: number, whole: number): number {
  if (!Number.isFinite(part) || !Number.isFinite(whole) || whole <= 0) return 0;
  return Math.min(Math.max(part / whole, 0), 1);
}

export function approvalFunnel(a: PanelApprovals): ApprovalFunnel {
  const decided = a.approved + a.rejected;
  const widest = Math.max(a.steps, a.gated, decided, a.approved, 0);

  const raw: { key: string; label: string; value: number }[] = [
    { key: 'steps', label: 'Çalışan adım', value: a.steps },
    { key: 'gated', label: 'Onay kapısına düştü', value: a.gated },
    { key: 'decided', label: 'İnsan karar verdi', value: decided },
    { key: 'approved', label: 'Onaylandı', value: a.approved },
  ];

  const stages = raw.map((stage, index) => ({
    ...stage,
    share: ratio(stage.value, widest),
    ofPrevious: index === 0 ? null : ratio(stage.value, raw[index - 1]!.value),
  }));

  const slices: FunnelSlice[] = [
    { key: 'asIs', label: 'Olduğu gibi onaylandı', value: a.approvedAsIs, color: 'var(--chart-done)' },
    { key: 'edited', label: 'Düzeltilip onaylandı', value: a.approvedWithEdit, color: 'var(--chart-touched)' },
    { key: 'rejected', label: 'Reddedildi', value: a.rejected, color: 'var(--chart-refused)' },
    { key: 'cancelled', label: 'Akış durdurulduğu için kapandı', value: a.cancelled, color: 'var(--chart-void)' },
    { key: 'pending', label: 'Kararını bekliyor', value: a.pending, color: 'var(--chart-waiting)' },
  ].map((slice) => ({ ...slice, share: ratio(slice.value, a.gated) }));

  const accounted = a.approvedAsIs + a.approvedWithEdit + a.rejected + a.cancelled + a.pending;

  return { stages, slices, gated: a.gated, unaccounted: a.gated - accounted };
}
