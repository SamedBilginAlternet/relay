import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ChevronDown, Loader } from 'lucide-react';
import { useId, useState } from 'react';
import { SOURCE_META, URGENCY_META, kindLabel } from './InsightCardView';
import { enterProps, expandProps } from '../lib/motion';
import type { InsightCard, SuggestedAction } from '../types/brief';

type Props = {
  card: InsightCard;
  index: number;
  busyTool: string | null;
  onAction: (card: InsightCard, action: SuggestedAction) => void;
  onDismiss: (cardId: string) => void;
};

/**
 * Everything under the focus card: one 44px line each — urgency, subject,
 * who. The summary and the suggested actions are one click away, so five
 * insights cost five lines instead of five screens.
 */
export function PriorityRow({ card, index, busyTool, onAction, onDismiss }: Props) {
  const reduce = useReducedMotion();
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const source = SOURCE_META[card.source] ?? SOURCE_META.gmail;
  const urgency = URGENCY_META[card.urgency] ?? URGENCY_META.normal;
  const busy = busyTool != null;
  const actionCount = card.suggestedActions.length;

  return (
    <motion.li className={`prow ${open ? 'prow--open' : ''}`} {...enterProps(index, reduce)}>
      <button
        type="button"
        className="prow__btn"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`prow__urgency ${urgency.className}`}>
          <urgency.Icon size={13} aria-hidden />
          <span className="prow__urgency-label">{urgency.label}</span>
        </span>

        <span className="prow__title">{card.title}</span>

        <span className="prow__from">
          <source.Icon size={13} aria-hidden />
          <span className="sr-only">{source.label} · </span>
          {card.from ? `${card.from} · ` : ''}
          {kindLabel(card.kind)}
        </span>

        <span className="prow__hint">
          <span className="prow__hint-text">
            {actionCount > 0 ? `${actionCount} öneri` : 'detay'}
          </span>
          <ChevronDown size={15} aria-hidden className="prow__chev" />
        </span>
      </button>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div key="body" id={bodyId} {...expandProps(reduce)}>
            <div className="prow__body">
              {card.summary && <p className="prow__summary">{card.summary}</p>}
              <div className="prow__actions">
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
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.li>
  );
}
