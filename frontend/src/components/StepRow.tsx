import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ChevronDown, ShieldQuestion, SkipForward, TriangleAlert, Wallet } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { stepDuration, formatTokens, formatUsd, modelLabel } from '../lib/format';
import { paramLabel } from '../lib/paramLabels';
import { DECISION_LABEL, stepStatusMeta } from '../lib/status';
import type { Step } from '../types/api';
import { BrandMark, providerOf } from './BrandMark';
import { ParamBlock } from './ParamBlock';

type Props = {
  step: Step;
  index: number;
  expanded: boolean;
  onToggle: (stepId: string) => void;
  readOnly?: boolean;
  busy?: boolean;
  rejecting?: boolean;
  /** Second argument: only the fields the user rewrote. Absent means "send what you see". */
  onApprove?: (stepId: string, params?: Record<string, unknown>) => void;
  onReject?: (stepId: string, reason: string) => void;
  onStartReject?: (stepId: string | null) => void;
  /** The server's answer to a refused edit — a sentence per field, plus one summary line. */
  editError?: { message: string; fields: Record<string, string> } | null;
};

/** Scalars a person can sensibly retype in a text box: a channel, a sentence, a status. */
function editableParams(params: Record<string, unknown>): [string, string | number][] {
  return Object.entries(params).filter(
    (entry): entry is [string, string | number] =>
      typeof entry[1] === 'string' || typeof entry[1] === 'number',
  );
}

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
  editError = null,
}: Props) {
  const reduce = useReducedMotion();
  const meta = stepStatusMeta(step.status);
  const Icon = meta.Icon;
  const [reason, setReason] = useState('');
  const reasonRef = useRef<HTMLInputElement>(null);
  const [, forceTick] = useState(0);
  /** Only the boxes the user actually typed in; everything else reads the live value. */
  const [edits, setEdits] = useState<Record<string, string>>({});

  // Live duration while the step is running.
  useEffect(() => {
    if (step.status !== 'running' && step.status !== 'awaiting_approval') return;
    // A parked step ticks too, but once a second and as a wait — half-second
    // updates on something nobody is watching would be a repaint for nothing.
    const id = setInterval(() => forceTick((n) => n + 1), step.status === 'running' ? 500 : 1000);
    return () => clearInterval(id);
  }, [step.status]);

  useEffect(() => {
    if (rejecting) reasonRef.current?.focus();
  }, [rejecting]);

  /*
    A step parked at the gate is not working. Live it read "27 dk 56 sn" next to
    a step whose tool had already returned and which was waiting on a person —
    the number was true as elapsed time and false as everything a duration means
    on this screen, where every other row's figure is how long a tool took.

    So a parked step shows how long it has been waiting, said as waiting. The
    two are different facts and they now read as different facts.
  */
  const waiting = step.status === 'awaiting_approval';
  const duration = waiting ? null : stepDuration(step.startedAt, step.finishedAt);
  const waited = waiting ? stepDuration(step.startedAt, null) : null;
  const awaiting = step.status === 'awaiting_approval';
  const showGate = awaiting && !readOnly;
  /**
   * The money gate, not the write gate. The two used to look the same on screen, so a run
   * that stopped because it had spent its budget told the user it needed permission to
   * write — and the button they pressed lifted the budget instead.
   */
  const budgetGate = step.pausedBy === 'budget';
  const fields = editableParams(step.params);
  const paramsKey = JSON.stringify(step.params);

  // The agent may replace the parameters while the step is parked — after a provider
  // rejection it comes back with new ones. Half-typed edits to values that no longer exist
  // would be approved against something the user never saw, so they go.
  useEffect(() => {
    setEdits({});
  }, [paramsKey]);

  const valueOf = (key: string, value: string | number): string =>
    edits[key] ?? String(value);

  /** Only what differs from the screen — approving untouched sends no params at all. */
  const changedParams = (): Record<string, unknown> | undefined => {
    const changed: Record<string, unknown> = {};
    for (const [key, value] of fields) {
      const typed = edits[key];
      if (typed === undefined || typed === String(value)) continue;
      changed[key] =
        typeof value === 'number' && typed.trim() !== '' && !Number.isNaN(Number(typed))
          ? Number(typed)
          : typed;
    }
    return Object.keys(changed).length > 0 ? changed : undefined;
  };
  const dirty = changedParams() !== undefined;

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
              {providerOf(step.toolName) && (
                <BrandMark provider={providerOf(step.toolName)!} size={12} />
              )}
              {step.toolName ?? 'araç yok — akıl yürütme'}
            </span>
            <span className="step__role">{step.role}</span>
            {/* Which model answered, where the row is read rather than opened: the point of
                routing is that the cheap steps are visibly cheap. Absent on a step with no
                model call, and absent is drawn as nothing at all. */}
            {step.model && (
              <span
                className="tool-chip tool-chip--model"
                title={`Bu adıma en çok token'ı ${step.model} yanıtladı`}
              >
                {modelLabel(step.model)}
              </span>
            )}
          </span>
        </span>

        <span className="step__right">
          <span className="step__duration">
            {waited ? `${waited} bekliyor` : (duration ?? '—')}
          </span>
          <motion.span
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={{ duration: reduce ? 0 : 0.2, ease: 'easeOut' }}
            style={{ display: 'grid', placeItems: 'center', color: 'var(--fg-muted)' }}
          >
            <ChevronDown size={16} aria-hidden />
          </motion.span>
        </span>
      </button>

      {/* The reason a skipped step did nothing, on the row itself. A skip is correct when
          the model is right and silent data loss when it is wrong — a reason only visible
          after a click is a wrong skip nobody notices. Muted and wordy, never colour alone:
          the glyph repeats the status icon and the sentence names what was not found. */}
      {step.status === 'skipped' && step.skipReason && (
        <div className="step__skipnote">
          <SkipForward size={14} aria-hidden />
          <span>Atlandı: {step.skipReason}</span>
        </div>
      )}

      {showGate && (
        <div className="gate">
          {/* The coverage check's sentence: this write targets a surface the goal never
              named. Amber — the "needs your judgement" colour — and above everything else
              in the gate, because run 85f1b3be was approved by a person who saw a
              plausible Jira write and no hint that the goal had asked for Notion. */}
          {step.warning && !budgetGate && (
            <span className="gate__warning">
              <TriangleAlert size={14} aria-hidden />
              {step.warning}
            </span>
          )}
          <span className="gate__note">
            {budgetGate ? <Wallet size={14} aria-hidden /> : <ShieldQuestion size={14} aria-hidden />}
            {budgetGate
              ? 'Bütçe doldu — bu adım değil, harcama sınırı bekletiyor. Devam edersen tavan bu akış için kalkar.'
              : 'Yazma adımı — çalışması için onayın gerekiyor.'}
          </span>

          {!rejecting && !budgetGate && fields.length > 0 && (
            <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {fields.map(([key, value]) => {
                const text = valueOf(key, value);
                const multiline = typeof value === 'string' && (text.length > 60 || text.includes('\n'));
                const problem = editError?.fields[key];
                const inputStyle = {
                  minHeight: multiline ? 72 : 40,
                  padding: '8px 12px',
                  borderRadius: 'var(--r-btn)',
                  border: `1px solid ${problem ? 'var(--danger)' : 'var(--border)'}`,
                  background: 'var(--bg)',
                  outline: 'none',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 13,
                  resize: 'vertical' as const,
                  width: '100%',
                };
                return (
                  <label className="field" key={key}>
                    {/* `.param-label`, not `.t-label`: the token uppercases, and
                        this text is data. See lib/paramLabels.ts. */}
                    <span className="param-label">{paramLabel(key)}</span>
                    {multiline ? (
                      <textarea
                        value={text}
                        rows={3}
                        disabled={busy}
                        style={inputStyle}
                        aria-invalid={problem ? true : undefined}
                        onChange={(e) => setEdits((cur) => ({ ...cur, [key]: e.target.value }))}
                      />
                    ) : (
                      <input
                        type="text"
                        value={text}
                        disabled={busy}
                        style={inputStyle}
                        aria-invalid={problem ? true : undefined}
                        onChange={(e) => setEdits((cur) => ({ ...cur, [key]: e.target.value }))}
                      />
                    )}
                    {problem && (
                      <span className="field__hint" style={{ color: 'var(--danger)' }}>
                        {problem}
                      </span>
                    )}
                  </label>
                );
              })}
              {editError && (
                <span className="field__hint" style={{ color: 'var(--danger)' }}>
                  {editError.message}
                </span>
              )}
              {dirty && !editError && (
                <span className="field__hint">
                  Değiştirdiğin değer olduğu gibi gönderilir — iz kaydına eski ve yeni hâliyle
                  düşer.
                </span>
              )}
            </div>
          )}

          {!rejecting ? (
            <>
              <button
                type="button"
                className="btn btn--sm"
                disabled={busy}
                onClick={() => onApprove?.(step.id, budgetGate ? undefined : changedParams())}
              >
                {budgetGate ? 'Bütçeyi bu akış için kaldır' : dirty ? 'Düzelt ve onayla' : 'Onayla'}
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
                {/* The full id, spelled out, next to the price it produced. The chip on the
                    row is a shorthand; this is the line someone checks it against. */}
                {step.model && <span>Yanıtlayan model: {step.model}</span>}
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

              {step.status === 'skipped' ? (
                /* The skip record itself ({"skipped":true,…}) is plumbing; the sentence is
                   the outcome. It repeats the row note on purpose — this panel is what gets
                   read when someone asks "what did this step produce", and the answer is
                   "nothing, and here is why". */
                <p className="t-caption">
                  {step.skipReason
                    ? `Adım atlandı: ${step.skipReason} Araç hiç çağrılmadı.`
                    : 'Adım atlandı — koşulu sağlayan bir şey bulunamadı, araç hiç çağrılmadı.'}
                </p>
              ) : step.result != null ? (
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
