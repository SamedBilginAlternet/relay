import { AnimatePresence } from 'motion/react';
import { ListChecks, Repeat2, TriangleAlert, Workflow } from 'lucide-react';
import type { StreamStatus } from '../data/RunSource';
import { runStatusMeta } from '../lib/status';
import type { RunPhase, StepEditError } from '../store/runStore';
import type { Run } from '../types/api';
import { CostBar } from './CostBar';
import { EmptyState } from './EmptyState';
import { StepRow } from './StepRow';

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
              description="Plan hazırlanıyor. İlk adım geldiğinde burada belirir — hiçbir şey arka planda gizlenmez."
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
