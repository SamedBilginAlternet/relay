import { motion, useReducedMotion } from 'motion/react';
import {
  AlertTriangle,
  CircleDot,
  GitPullRequest,
  ListOrdered,
  Loader,
  Mail,
  SquareKanban,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { enterProps } from '../lib/motion';
import type { InsightCard, InsightSource, InsightUrgency, SuggestedAction } from '../types/brief';

type Props = {
  card: InsightCard;
  index: number;
  /** `digest.priorities[].why` — why this one earned the top slot. Often absent. */
  why?: string | null;
  busyTool: string | null;
  onAction: (card: InsightCard, action: SuggestedAction) => void;
  onDismiss: (cardId: string) => void;
};

export const SOURCE_META: Record<InsightSource, { Icon: LucideIcon; label: string }> = {
  gmail: { Icon: Mail, label: 'E-posta' },
  github: { Icon: GitPullRequest, label: 'GitHub' },
  jira: { Icon: SquareKanban, label: 'Jira' },
};

/** Colour never carries the meaning alone — icon + word always ride along. */
export const URGENCY_META: Record<
  InsightUrgency,
  { label: string; className: string; Icon: LucideIcon }
> = {
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

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind.replace(/_/g, ' ');
}

/**
 * THE one card. Only the single most urgent insight is rendered this big —
 * everything else drops to a one-line row (PriorityRow). A stack of five
 * equally loud cards is a list, not a priority.
 */
export function InsightCardView({ card, index, why, busyTool, onAction, onDismiss }: Props) {
  const reduce = useReducedMotion();
  const source = SOURCE_META[card.source] ?? SOURCE_META.gmail;
  const urgency = URGENCY_META[card.urgency] ?? URGENCY_META.normal;
  const busy = busyTool != null;

  return (
    <motion.article className={`focus focus--${card.urgency}`} {...enterProps(index, reduce)}>
      <div className="focus__head">
        <span className="focus__source" title={source.label}>
          <source.Icon size={16} aria-hidden />
          <span className="sr-only">{source.label}</span>
        </span>

        <div className="focus__who">
          <h3 className="focus__title">{card.title}</h3>
          <p className="focus__from">
            {card.from ? <strong>{card.from}</strong> : null}
            {card.from ? ' · ' : null}
            {kindLabel(card.kind)}
          </p>
        </div>

        <span className={`urgency ${urgency.className}`}>
          <urgency.Icon size={13} aria-hidden />
          {urgency.label}
        </span>
      </div>

      {/*
        The ranking rationale, not a second summary: `summary` says what the thing
        is, this says why it outranked everything else today. It leads the body
        because "why am I looking at this first" is the question the big slot raises.
      */}
      {why ? (
        <p className="focus__why">
          <ListOrdered size={13} aria-hidden />
          <span>
            <span className="sr-only">Sıralama gerekçesi: </span>
            {why}
          </span>
        </p>
      ) : null}

      {card.summary && <p className="focus__summary">{card.summary}</p>}

      <div className="focus__actions">
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
              {thisBusy && <Loader size={14} aria-hidden className="spin" />}
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
