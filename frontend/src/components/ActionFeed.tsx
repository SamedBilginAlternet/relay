import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
  CalendarClock,
  ChevronDown,
  ListOrdered,
  Loader,
  Plug,
  TriangleAlert,
} from 'lucide-react';
import { useId, useState } from 'react';
import { SOURCE_META, URGENCY_META, kindLabel } from '../lib/insight';
import { expandProps, feedRowProps } from '../lib/motion';
import type { InsightCard, SuggestedAction } from '../types/brief';

type RowProps = {
  card: InsightCard;
  /**
   * Place in the feed, 0-based. It is both the stagger slot — the wave has to
   * read as one, so it counts across the whole feed — and, plus one, the number
   * the user sees. Those used to be two props and they disagreed: with a
   * meeting on top, the meeting was drawn first with no number and the row
   * under it still called itself "1", so the list appeared to start at zero.
   */
  index: number;
  /** `digest.priorities[].why` — why this one is here now. Often absent. */
  why?: string | null;
  busyTool: string | null;
  onAction: (card: InsightCard, action: SuggestedAction) => void;
  onDismiss: (cardId: string) => void;
};

/** `jira:KAN-42` → `KAN-42`. Anything else is not a record and gets nothing. */
function jiraKey(cardId: string): string | null {
  const match = /^jira:([A-Z][A-Z0-9_]*-\d+)$/.exec(cardId);
  return match?.[1] ?? null;
}

/**
 * Actions the screen knows about that the brief did not suggest.
 *
 * Reading a record's comments is the one the user asked for by name — "jira
 * ticket yorumlarını getir" — and it is a READ, so it never opens the approval
 * gate. The classifier does not propose it because it cannot know whether the
 * discussion is the interesting part; the human clicking the row does.
 */
function extraActions(card: InsightCard): SuggestedAction[] {
  const key = card.source === 'jira' ? jiraKey(card.id) : null;
  if (!key) return [];
  if (card.suggestedActions.some((action) => action.tool === 'jira.getComments')) return [];
  return [{ tool: 'jira.getComments', label: 'Yorumları getir', params: { issueKey: key } }];
}

/**
 * One job in the feed.
 *
 * The shape answers three questions in the order they get asked: WHAT (title,
 * with the source demoted to a badge), WHY NOW (the digest's reason, or the
 * summary when there is none), and DO IT — the first suggested action as a
 * button that is already on the screen. That last part is the whole issue: the
 * action used to be behind a disclosure, so "one click" was two, and the click
 * that came first revealed nothing the user had asked for.
 *
 * Everything else — the rest of the suggestions, the full summary, who it is
 * from — stays one click down, because a feed that shows everything is the
 * section grid again with different borders.
 */
export function ActionRow({ card, index, why, busyTool, onAction, onDismiss }: RowProps) {
  const reduce = useReducedMotion();
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const source = SOURCE_META[card.source] ?? SOURCE_META.gmail;
  const urgency = URGENCY_META[card.urgency] ?? URGENCY_META.normal;
  const busy = busyTool != null;
  const [primary, ...rest] = card.suggestedActions;
  const others = [...rest, ...extraActions(card)];
  const primaryBusy = primary != null && busyTool === primary.tool;
  // The reason takes the line when there is one; otherwise the summary does.
  const line = why ?? (card.summary || null);

  return (
    <motion.li
      className={`arow${open ? ' arow--open' : ''} arow--${card.urgency}`}
      {...feedRowProps(index, reduce)}
    >
      <div className="arow__line">
        <button
          type="button"
          className="arow__open"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((v) => !v)}
        >
          <span className="arow__rank" aria-hidden>
            {index + 1}
          </span>

          <span className="arow__text">
            <span className="arow__head">
              <span className="src-badge">
                <source.Icon size={12} aria-hidden />
                {source.label}
              </span>
              {card.urgency === 'high' && (
                <span className={`urgency urgency--sm ${urgency.className}`}>
                  <urgency.Icon size={11} aria-hidden />
                  {urgency.label}
                </span>
              )}
              <span className="arow__title">{card.title}</span>
            </span>

            {line && (
              <span className="arow__why">
                {why ? (
                  <>
                    <ListOrdered size={12} aria-hidden />
                    <span className="sr-only">Neden şimdi: </span>
                  </>
                ) : null}
                <span className="arow__why-text">{line}</span>
              </span>
            )}
          </span>

          <span className="arow__chev" aria-hidden>
            <ChevronDown size={16} />
          </span>
          <span className="sr-only">
            {open ? 'Ayrıntıyı kapat' : 'Ayrıntıyı aç'}
          </span>
        </button>

        {primary && (
          <button
            type="button"
            className="btn btn--sm action-pill arow__do"
            disabled={busy}
            onClick={() => onAction(card, primary)}
            title={primary.tool}
          >
            {primaryBusy && <Loader size={14} aria-hidden className="spin" />}
            {primaryBusy ? 'Başlatılıyor…' : primary.label}
          </button>
        )}
      </div>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div key="body" id={bodyId} {...expandProps(reduce)}>
            <div className="arow__body">
              <p className="arow__meta">
                <span className="sr-only">{source.label} · </span>
                {card.from ? `${card.from} · ` : ''}
                {kindLabel(card.kind)}
                {card.urgency !== 'high' ? ` · ${urgency.label} öncelik` : ''}
              </p>
              {/* The reason displaced the summary upstairs — show it once, here. */}
              {why && card.summary ? <p className="arow__summary">{card.summary}</p> : null}
              <div className="arow__actions">
                {others.map((action) => {
                  const thisBusy = busyTool === action.tool;
                  return (
                    <button
                      key={`${action.tool}-${action.label}`}
                      type="button"
                      className="btn btn--sm btn--outline"
                      disabled={busy}
                      onClick={() => onAction(card, action)}
                      title={action.tool}
                    >
                      {thisBusy && <Loader size={14} aria-hidden className="spin" />}
                      {thisBusy ? 'Başlatılıyor…' : action.label}
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

type MeetingProps = {
  index: number;
  /** The meeting's own words: title, room/person, start time in the user's zone. */
  title: string;
  detail: string;
  /** `null` while the flow catalogue is still loading or the flow cannot run. */
  onPrepare: (() => void) | null;
  busy: boolean;
};

/**
 * The next meeting, as the one thing you can do about it before walking in.
 *
 * A meeting is context, not a task — the backend keeps it out of the classifier
 * on purpose — but "toplantıya katılmadan önce şuna bak" is the sentence this
 * whole screen was rewritten for, and the prep flow makes it real: it reads the
 * event, finds the records and mail its title points at, and cites them.
 *
 * It is numbered like everything else in the list. It used to wear a clock in
 * the circle instead, which made the row under it — the one labelled "1" — look
 * like the first thing on a list that had already started. The clock is still
 * on the row, in the badge that says "Takvim"; what a meeting is stays legible
 * without spending the one place on the row that says where you are.
 */
export function MeetingRow({ index, title, detail, onPrepare, busy }: MeetingProps) {
  const reduce = useReducedMotion();

  return (
    <motion.li className="arow arow--meet" {...feedRowProps(index, reduce)}>
      <div className="arow__line">
        <div className="arow__open arow__open--static">
          <span className="arow__rank" aria-hidden>
            {index + 1}
          </span>
          <span className="arow__text">
            <span className="arow__head">
              <span className="src-badge">
                <CalendarClock size={12} aria-hidden />
                Takvim
              </span>
              <span className="arow__title">{title}</span>
            </span>
            <span className="arow__why">
              <span className="arow__why-text">{detail}</span>
            </span>
          </span>
        </div>

        {onPrepare && (
          <button
            type="button"
            className="btn btn--sm action-pill arow__do"
            disabled={busy}
            onClick={onPrepare}
          >
            {busy && <Loader size={14} aria-hidden className="spin" />}
            {busy ? 'Başlatılıyor…' : 'Toplantıya hazırlan'}
          </button>
        )}
      </div>
    </motion.li>
  );
}

type GapProps = {
  index: number;
  status: 'unavailable' | 'error';
  /** "Google Calendar", "Jira" — the thing that is missing, by name. */
  provider: string;
  /** Which part of the day goes dark without it. */
  scope: string;
  /** The provider's own words, when it had any. */
  reason?: string | null;
  onAction: () => void;
};

/**
 * A missing integration, as a job rather than as a footnote.
 *
 * The section grid is collapsed now, and the "connect me" cards used to live
 * inside it — which would have made an unconnected Jira invisible on the one
 * screen where it matters. Promoting the gap to a feed row is the honest fix:
 * it is, literally, something the user has to do before the day works, and it
 * reads as one. It sorts last because it is not today's work; it is why some
 * of today's work is missing.
 */
export function GapRow({ index, status, provider, scope, reason, onAction }: GapProps) {
  const reduce = useReducedMotion();
  const failed = status === 'error';

  return (
    <motion.li
      className={`arow arow--gap${failed ? ' arow--gap-error' : ''}`}
      {...feedRowProps(index, reduce)}
    >
      <div className="arow__line">
        <div className="arow__open arow__open--static">
          <span className="arow__rank" aria-hidden>
            {index + 1}
          </span>
          <span className="arow__text">
            <span className="arow__head">
              {/* The icon moved off the number circle and into a badge, where it
                  keeps its word next to it — the row is numbered like the rest
                  of the list now, and an amber edge on its own is not a label. */}
              <span className="src-badge">
                {failed ? <TriangleAlert size={12} aria-hidden /> : <Plug size={12} aria-hidden />}
                Bağlantı
              </span>
              <span className="arow__title">
                {provider} {failed ? 'yanıt vermedi' : 'bağlı değil'}
              </span>
            </span>
            <span className="arow__why">
              <span className="arow__why-text">{reason || `${scope} bu yüzden eksik.`}</span>
            </span>
          </span>
        </div>

        <button type="button" className="btn btn--sm btn--outline arow__do" onClick={onAction}>
          {failed ? 'Tekrar dene' : 'Bağlantılar’a git'}
        </button>
      </div>
    </motion.li>
  );
}
