import { AnimatePresence } from 'motion/react';
import { ListChecks, Repeat2, TriangleAlert, Workflow } from 'lucide-react';
import type { StreamStatus } from '../data/RunSource';
import { runStatusMeta } from '../lib/status';
import type { RunPhase, StepEditError } from '../store/runStore';
import type { Run, Step } from '../types/api';
import { formatUsd } from '../lib/format';
import { CostBar } from './CostBar';
import { EmptyState } from './EmptyState';
import { StepRow } from './StepRow';

/** The two sums behind the comparison line, and the count of steps they are made of. */
export type PremiumComparison = {
  steps: number;
  actualUsd: number;
  premiumUsd: number;
  differenceUsd: number;
};

/**
 * What the steps cost, against what the same tokens would have cost billed entirely at the
 * strong model's price.
 *
 * <p>This is the product's whole pitch reduced to two numbers, so the only thing it may ever
 * be is arithmetic on the token counts that were actually measured. Three ways it could stop
 * being that, and what happens instead:
 *
 * <ul>
 *   <li>A step that spent money but carries no premium figure would be present on one side
 *       of the sum and missing from the other. Two totals that are not about the same work
 *       are worse than no line, so there is no line.
 *   <li>A run where every call already went to the strong model has nothing to compare —
 *       equal sums draw nothing rather than a saving of zero.
 *   <li>Model calls that belong to no step (planning, whole-run verification) are counted in
 *       the run's total but have no premium price of their own, so they are outside this
 *       sum. That is why the sentence on screen is about the run's *steps*: it must not read
 *       as a second, smaller answer to what the run cost.
 * </ul>
 */
export function comparePremium(steps: Step[]): PremiumComparison | null {
  let actualUsd = 0;
  let premiumUsd = 0;
  let counted = 0;
  for (const step of steps) {
    const premium = step.premiumCostUsd;
    if (typeof premium === 'number' && Number.isFinite(premium)) {
      actualUsd += step.costUsd;
      premiumUsd += premium;
      counted += 1;
    } else if (step.costUsd > 0) {
      return null;
    }
  }
  if (counted === 0) return null;
  const differenceUsd = premiumUsd - actualUsd;
  // Money is kept to six decimals end to end; a difference below that is a difference the
  // product cannot show, and "fark $0.000000" is not a comparison.
  if (Math.round(differenceUsd * 1e6) === 0) return null;
  return { steps: counted, actualUsd, premiumUsd, differenceUsd };
}

type Props = {
  run: Run | null;
  phase: RunPhase;
  error: string | null;
  streamStatus: StreamStatus | 'idle';
  expandedStepId: string | null;
  rejectingStepId?: string | null;
  busyStepId?: string | null;
  /** A refused parameter edit, shown on the step it belongs to and nowhere else. */
  editError?: StepEditError | null;
  readOnly?: boolean;
  onToggleStep: (stepId: string) => void;
  onApprove?: (stepId: string, params?: Record<string, unknown>) => void;
  onReject?: (stepId: string, reason: string) => void;
  onStartReject?: (stepId: string | null) => void;
  onRetry?: () => void;
  onRerun?: () => void;
};

export function WorkflowPanel(props: Props) {
  const {
    run,
    phase,
    error,
    streamStatus,
    expandedStepId,
    rejectingStepId,
    busyStepId,
    editError,
    readOnly = false,
    onToggleStep,
    onApprove,
    onReject,
    onStartReject,
    onRetry,
    onRerun,
  } = props;

  const meta = run ? runStatusMeta(run.status) : null;
  const awaitingCount = run?.steps.filter((s) => s.status === 'awaiting_approval').length ?? 0;
  const doneCount = run?.steps.filter((s) => s.status === 'done').length ?? 0;
  const premium = run ? comparePremium(run.steps) : null;

  return (
    <section className="workflow-col" aria-label="İş akışı paneli">
      <div className="panel-head">
        <CostBar run={run} streamStatus={streamStatus} showStream={!readOnly} />
        <div
          className="cost-bar"
          style={{ borderTop: '1px solid var(--border)', paddingTop: 8, paddingBottom: 8, gap: 12 }}
        >
          <span className="t-label" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Workflow size={12} aria-hidden />
            Akış
          </span>
          {meta && (
            <span className={`status-pill ${meta.className}`}>
              <meta.Icon size={13} aria-hidden />
              {meta.label}
            </span>
          )}
          <span className="t-caption">
            {run ? `${doneCount}/${run.steps.length} adım tamam` : 'Akış yok'}
            {awaitingCount > 0 ? ` · ${awaitingCount} onay bekliyor` : ''}
          </span>
          <div className="cost-bar__spacer" />
          {onRerun && run && (
            <button type="button" className="btn btn--outline btn--sm" onClick={onRerun}>
              <Repeat2 size={14} aria-hidden />
              Tekrar çalıştır
            </button>
          )}
        </div>

        {/* Two sums and their difference — the only sentence in the product that says what
            the routing is for. It says nothing about time or effort, because it knows
            nothing about either: it is the same measured tokens at two price lists. */}
        {premium && (
          <div
            className="cost-bar"
            style={{ borderTop: '1px solid var(--border)', paddingTop: 8, paddingBottom: 8 }}
          >
            <span
              className="t-caption"
              title="Karşılaştırma yalnız güçlü model fiyatı hesaplanabilen adımları kapsar. Planlama gibi bir adıma bağlı olmayan çağrılar akış toplamında vardır, bu satırda yoktur."
            >
              {`Bu akışın ${premium.steps} adımı ${formatUsd(premium.actualUsd)} tuttu; aynı token'lar tümüyle güçlü modelde ${formatUsd(premium.premiumUsd)} tutardı — fark ${formatUsd(premium.differenceUsd)}.`}
            </span>
          </div>
        )}
      </div>

      <ol
        className="steps"
        role="status"
        aria-live="polite"
        aria-relevant="additions text"
        aria-label="Adım zaman çizelgesi"
        style={{ listStyle: 'none', margin: 0 }}
      >
        {phase === 'error' && (
          <li>
            <div className="notice notice--danger" style={{ marginBottom: 12 }}>
              <TriangleAlert size={16} aria-hidden />
              <span>{error ?? 'Akış yüklenemedi.'}</span>
            </div>
            {onRetry && (
              <button type="button" className="btn btn--outline btn--sm" onClick={onRetry}>
                Tekrar dene
              </button>
            )}
          </li>
        )}

        {phase !== 'error' && (phase === 'creating' || phase === 'loading') && (
          <li aria-label="Yükleniyor">
            <p className="t-caption" style={{ padding: '4px 4px 12px' }}>
              {phase === 'creating'
                ? 'Planlayıcı hedefi adımlara bölüyor…'
                : 'Akış yükleniyor…'}
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div className="skeleton" />
              <div className="skeleton" style={{ opacity: 0.7 }} />
              <div className="skeleton" style={{ opacity: 0.4 }} />
            </div>
          </li>
        )}

        {phase === 'ready' && run && run.steps.length === 0 && (
          <li>
            <EmptyState
              Icon={ListChecks}
              title="Henüz adım yok"
              description="Plan hazırlanıyor."
            />
          </li>
        )}

        {phase === 'idle' && !run && (
          <li>
            <EmptyState
              Icon={Workflow}
              title="Çalışan akış yok"
              description="Soldaki alana ne yapılmasını istediğini yaz; Relay adımları buraya sıralar."
            />
          </li>
        )}

        <AnimatePresence initial={false}>
          {run?.steps.map((step, i) => (
            <StepRow
              key={step.id}
              step={step}
              index={i}
              expanded={expandedStepId === step.id}
              onToggle={onToggleStep}
              readOnly={readOnly}
              busy={busyStepId === step.id}
              rejecting={rejectingStepId === step.id}
              editError={editError?.stepId === step.id ? editError : null}
              onApprove={onApprove}
              onReject={onReject}
              onStartReject={onStartReject}
            />
          ))}
        </AnimatePresence>
      </ol>
    </section>
  );
}
