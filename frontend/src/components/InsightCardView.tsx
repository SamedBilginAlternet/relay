import { motion, useReducedMotion } from 'motion/react';
import {
  AlertTriangle,
  ArrowRight,
  CircleDot,
  GitPullRequest,
  Loader,
  Mail,
  ShieldQuestion,
  SquareKanban,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { InsightCard, InsightSource, InsightUrgency, SuggestedAction } from '../types/brief';

type Props = {
  card: InsightCard;
  index: number;
  busyTool: string | null;
  onAction: (card: InsightCard, action: SuggestedAction) => void;
  onDismiss: (cardId: string) => void;
};

const SOURCE_META: Record<InsightSource, { Icon: LucideIcon; label: string }> = {
  gmail: { Icon: Mail, label: 'E-posta' },
  github: { Icon: GitPullRequest, label: 'GitHub' },
  jira: { Icon: SquareKanban, label: 'Jira' },
};

/** Colour never carries the meaning alone — icon + word always ride along. */
const URGENCY_META: Record<InsightUrgency, { label: string; className: string; Icon: LucideIcon }> = {
  high: { label: 'Acil', className: 'urgency--high', Icon: AlertTriangle },
  normal: { label: 'Normal', className: 'urgency--normal', Icon: CircleDot },
  low: { label: 'Düşük', className: 'urgency--low', Icon: CircleDot },
};

const KIND_LABEL: Record<string, string> = {
  bug_report: 'hata bildirimi',
  request: 'istek',
  fyi: 'bilgilendirme',
  needs_reply: 'yanıt bekliyor',
  scheduling: 'takvim',
};

export function InsightCardView({ card, index, busyTool, onAction, onDismiss }: Props) {
  const reduce = useReducedMotion();
  const source = SOURCE_META[card.source] ?? SOURCE_META.gmail;
  const urgency = URGENCY_META[card.urgency] ?? URGENCY_META.normal;
  const kind = KIND_LABEL[card.kind] ?? card.kind.replace(/_/g, ' ');
  const busy = busyTool != null;

  return (
    <motion.article
      className={`insight insight--${card.urgency}`}
      // DESIGN.md §4 — 300ms enter, 40ms stagger, transform/opacity only.
      initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(10px)' }}
      animate={{ opacity: 1, transform: 'translateY(0px)' }}
      exit={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(-6px)' }}
      transition={{
        duration: reduce ? 0.15 : 0.3,
        delay: reduce ? 0 : Math.min(index, 5) * 0.04,
        ease: [0.16, 1, 0.3, 1],
      }}
    >
      <div className="insight__head">
        <span className="insight__source" title={source.label}>
          <source.Icon size={16} aria-hidden />
          <span className="sr-only">{source.label}</span>
        </span>

        <div className="insight__who">
          <h3 className="insight__title">{card.title}</h3>
          <p className="insight__from">
            {card.from ? <strong>{card.from}</strong> : null}
            {card.from ? ' · ' : null}
            {kind}
          </p>
        </div>

        <span className={`urgency ${urgency.className}`}>
          <urgency.Icon size={13} aria-hidden />
          {urgency.label}
        </span>
      </div>

      <p className="insight__summary">
        <ArrowRight size={14} aria-hidden className="insight__arrow" />
        {card.summary}
      </p>

      <div className="insight__actions">
        {card.suggestedActions.map((action) => {
          const thisBusy = busyTool === action.tool;
          return (
            <button
              key={`${action.tool}-${action.label}`}
              type="button"
              className="btn btn--sm action-pill"
              disabled={busy}
              onClick={() => onAction(card, action)}
              title={action.tool}
            >
              {thisBusy ? (
                <Loader size={14} aria-hidden className="spin" />
              ) : (
                <ShieldQuestion size={14} aria-hidden />
              )}
              {thisBusy ? 'Akış başlatılıyor…' : action.label}
            </button>
          );
        })}

        <button
          type="button"
          className="btn btn--ghost btn--sm"
          disabled={busy}
          onClick={() => onDismiss(card.id)}
        >
          Yoksay
        </button>
      </div>
    </motion.article>
  );
}
