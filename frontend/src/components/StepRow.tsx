import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ChevronDown, ShieldQuestion, TriangleAlert } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { stepDuration, formatTokens, formatUsd } from '../lib/format';
import { DECISION_LABEL, stepStatusMeta } from '../lib/status';
import type { Step } from '../types/api';
import { ParamBlock } from './ParamBlock';

type Props = {
  step: Step;
  index: number;
  expanded: boolean;
  onToggle: (stepId: string) => void;
  readOnly?: boolean;
  busy?: boolean;
  rejecting?: boolean;
  onApprove?: (stepId: string) => void;
  onReject?: (stepId: string, reason: string) => void;
  onStartReject?: (stepId: string | null) => void;
};

export function StepRow({
  step,
  index,
  expanded,
  onToggle,
  readOnly = false,
  busy = false,
  rejecting = false,
  onApprove,
  onReject,
  onStartReject,
}: Props) {
  const reduce = useReducedMotion();
  const meta = stepStatusMeta(step.status);
  const Icon = meta.Icon;
  const [reason, setReason] = useState('');
  const reasonRef = useRef<HTMLInputElement>(null);
  const [, forceTick] = useState(0);

  // Live duration while the step is running.
  useEffect(() => {
    if (step.status !== 'running') return;
    const id = setInterval(() => forceTick((n) => n + 1), 500);
    return () => clearInterval(id);
  }, [step.status]);

  useEffect(() => {
    if (rejecting) reasonRef.current?.focus();
  }, [rejecting]);

  const duration = stepDuration(step.startedAt, step.finishedAt);
  const awaiting = step.status === 'awaiting_approval';
  const showGate = awaiting && !readOnly;

  const rowClass = [
    'step',
    step.status === 'running' ? 'step--active' : '',
    awaiting ? 'step--awaiting' : '',
    step.status === 'failed' ? 'step--failed' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <motion.li
      className={rowClass}
      initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(8px)' }}
      animate={{ opacity: 1, transform: 'translateY(0px)' }}
      transition={{
        duration: reduce ? 0.2 : 0.3,
        delay: reduce ? 0 : Math.min(index, 8) * 0.04,
        ease: [0.16, 1, 0.3, 1],
      }}
    >
      <button
        type="button"
        className="step__row"
        onClick={() => onToggle(step.id)}
        aria-expanded={expanded}
        aria-label={`Adım ${step.ordinal}: ${step.title} — ${meta.label}. Parametreleri ${expanded ? 'gizle' : 'göster'}`}
      >
        <span className="step__ordinal" aria-hidden>
          {step.ordinal}
        </span>
        <motion.span
          className={`step__icon ${meta.className}`}
          animate={
            meta.pulse && !reduce ? { scale: [1, 1.12, 1], opacity: [1, 0.7, 1] } : { scale: 1, opacity: 1 }
          }
          transition={
            meta.pulse && !reduce
              ? { duration: 1.6, repeat: Infinity, ease: 'easeInOut' }
              : { duration: 0.25, ease: 'easeOut' }
          }
        >
          <Icon size={14} className={meta.spin && !reduce ? 'spin' : undefined} aria-hidden />
        </motion.span>

        <span className="step__main">
          <span className="step__title">{step.title}</span>
          <span className="step__meta">
            <span className={`tool-chip ${step.toolName ? '' : 'tool-chip--none'}`}>
              {step.toolName ?? 'araç yok — akıl yürütme'}
            </span>
            <span className="step__role">{step.role}</span>
          </span>
        </span>

        <span className="step__right">
          <span className="step__duration">{duration ?? '—'}</span>
          <motion.span
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={{ duration: reduce ? 0 : 0.2, ease: 'easeOut' }}
            style={{ display: 'grid', placeItems: 'center', color: 'var(--fg-muted)' }}
          >
            <ChevronDown size={16} aria-hidden />
          </motion.span>
        </span>
      </button>

      {showGate && (
        <div className="gate">
          <span className="gate__note">
            <ShieldQuestion size={14} aria-hidden />
            Yazma adımı — çalışması için onayın gerekiyor.
          </span>
          {!rejecting ? (
            <>
              <button
                type="button"
                className="btn btn--sm"
                disabled={busy}
                onClick={() => onApprove?.(step.id)}
              >
                Onayla
              </button>
              <button
                type="button"
                className="btn btn--outline btn--sm"
                disabled={busy}
                onClick={() => onStartReject?.(step.id)}
              >
                Reddet
              </button>
            </>
          ) : (
            <form
              className="gate__reason"
              onSubmit={(e) => {
                e.preventDefault();
                onReject?.(step.id, reason);
                setReason('');
              }}
            >
              <input
                ref={reasonRef}
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Neden reddediyorsun? (ajana geri gider)"
                aria-label="Reddetme gerekçesi"
                maxLength={200}
              />
              <button type="submit" className="btn btn--danger-outline btn--sm" disabled={busy}>
                Gönder
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => onStartReject?.(null)}
                disabled={busy}
              >
                Vazgeç
              </button>
            </form>
          )}
        </div>
      )}

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            className="step__body"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: reduce ? 0 : 0.2, ease: 'easeOut' }}
          >
            <div className="step__body-inner">
              <div className="run-card__meta">
                <span>Durum: {meta.label}</span>
                {step.decision && <span>Karar: {DECISION_LABEL[step.decision] ?? step.decision}</span>}
                <span>{formatTokens(step.tokens)} token</span>
                <span>{formatUsd(step.costUsd)}</span>
              </div>

              <ParamBlock title="Parametreler" value={step.params} />

              {step.rejectReason && (
                <div className="notice notice--warn">
                  <TriangleAlert size={14} aria-hidden />
                  <span>Reddetme gerekçesi: {step.rejectReason}</span>
                </div>
              )}

              {step.error && (
                <div className="error-box">
                  <TriangleAlert size={14} aria-hidden />
                  <span>{step.error}</span>
                </div>
              )}

              {step.result != null ? (
                <ParamBlock title="Sonuç" value={step.result} />
              ) : (
                <p className="t-caption">
                  {step.status === 'pending'
                    ? 'Bu adım henüz çalışmadı — sonuç yok.'
                    : step.status === 'rejected'
                      ? 'Adım reddedildi, araç hiç çağrılmadı.'
                      : 'Sonuç bekleniyor…'}
                </p>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.li>
  );
}
