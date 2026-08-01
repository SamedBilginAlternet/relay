import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
  CalendarClock,
  ChevronDown,
  ExternalLink,
  ListOrdered,
  ShieldCheck,
  Loader,
  Plug,
  TriangleAlert,
} from 'lucide-react';
import { useId, useState } from 'react';
import { BrandMark, providerOf } from './BrandMark';
import { actionLabel } from '../lib/actionLabels';
import { paramLabel } from '../lib/paramLabels';
import { SOURCE_META, URGENCY_META, kindLabel, reasonEarnsItsLine } from '../lib/insight';
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
  /**
   * Is this the one row whose action gets the filled accent button?
   *
   * Exactly one row on the screen may say yes. See {@link primaryClass}.
   */
  primary: boolean;
  /**
   * `digest.priorities[].why` — why this one is here now. Often absent, and
   * shown only when it beats the title; see {@link reasonEarnsItsLine}.
   *
   * <p>Already rationed by the caller: when two rows' reasons say the same thing only
   * the higher-ranked one is handed a sentence (#141).
   */
  why?: string | null;
  /**
   * What this row is, where it stands, how old it is — at most three machine tokens,
   * already made distinct from every other visible row's (see {@link dedupeStrips}).
   *
   * <p>It replaced the explanation sentence, which on the live screen was the same
   * sentence on three consecutive rows. Empty means this row had nothing of its own to
   * say, and it prints nothing rather than something invented.
   */
  facts?: string[];
  busyTool: string | null;
  onAction: (card: InsightCard, action: SuggestedAction) => void;
  onDismiss: (cardId: string) => void;
};

/**
 * Primary or Secondary, DESIGN.md §3.
 *
 * Every row's button used to be the same soft-purple pill: the ACİL row and a
 * pull request that had waited 262 days invited the eye equally, six times over,
 * so the screen never said out loud which thing to do first. DESIGN.md §1 spends
 * the accent on "the primary button" — singular — and one screen gets one.
 *
 * The difference survives greyscale on purpose: filled dark against outlined
 * white, not two tints of the same hue.
 */
function primaryClass(primary: boolean): string {
  return `btn btn--sm arow__do${primary ? '' : ' btn--outline'}`;
}

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
export function ActionRow({
  card,
  index,
  primary: isPrimary,
  why,
  facts = [],
  busyTool,
  onAction,
  onDismiss,
}: RowProps) {
  const reduce = useReducedMotion();
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const source = SOURCE_META[card.source] ?? SOURCE_META.gmail;
  const urgency = URGENCY_META[card.urgency] ?? URGENCY_META.normal;
  const busy = busyTool != null;
  const [primary, ...rest] = card.suggestedActions;
  const others = [...rest, ...extraActions(card)];
  const primaryBusy = primary != null && busyTool === primary.tool;
  /*
    The reason takes the line when there is one; otherwise the summary does —
    but only if either of them says something the title above it does not.

    The model writes both, and left alone it writes the title back: the row
    "Kurulum notunu README'ye ekle" was justified with "Kurulum notunun
    README'ye eklenmesi gerekiyor." (#67, #55). A row is allowed to say nothing
    under its title; it is not allowed to say the title twice. Whatever is
    dropped here is still one click down, in the body.
  */
  /*
    The summary is no longer a fallback for a missing reason. It was, and it is written by
    the same model in the same voice about the same item, so a row whose reason was
    dropped for repeating its neighbour simply printed the neighbour's other sentence
    instead (#141). The facts below the title carry what the row has to say; the summary
    is in the body, once.
  */
  const line = why && reasonEarnsItsLine(card.title, why) ? why : null;

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
              {/* The mark alone. The word beside it — "GitHub", "E-posta" — said what
                  the mark says, on every row, and the line under the title now names
                  the repository or the person, which is the part that differs. */}
              <BrandMark provider={card.source} size={14} />
              <span className="sr-only">{source.label} · </span>
              <span className="arow__title">{card.title}</span>
            </span>

            {/* Machine facts, same grammar every row, different values. `ACİL` is drawn
                apart from them because it is a warning rather than a description — and
                it is a word, not only a colour, with the row's left edge behind it. */}
            {(card.urgency === 'high' || facts.length > 0) && (
              <span className="arow__facts t-mono">
                {card.urgency === 'high' && (
                  <span className="arow__tok arow__tok--acil">{urgency.label}</span>
                )}
                {facts.map((token, i) => (
                  <span key={token}>
                    {(i > 0 || card.urgency === 'high') && (
                      <span className="arow__sep" aria-hidden>
                        {' · '}
                      </span>
                    )}
                    {token}
                  </span>
                ))}
              </span>
            )}

            {line && (
              <span className="arow__why">
                <ListOrdered size={12} aria-hidden />
                <span className="sr-only">Neden şimdi: </span>
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
            className={primaryClass(isPrimary)}
            disabled={busy}
            onClick={() => onAction(card, primary)}
            title={primary.tool}
          >
            {primaryBusy && <Loader size={14} aria-hidden className="spin" />}
            {primaryBusy ? 'Başlatılıyor…' : actionLabel(primary.tool, primary.label)}
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
              {/* Whatever is not on the line upstairs — the reason displaced it,
                  or it repeated the title and was dropped — is readable here.
                  Once, though: a summary already showing above is not repeated. */}
              {card.summary && card.summary !== line ? (
                <p className="arow__summary">{card.summary}</p>
              ) : null}

              {/*
                The way out of the black box. The card is a model's reading of
                something that exists somewhere else, and the reader who does not
                believe the reading has to be one click from the original —
                otherwise "özetledim, güven bana" is the whole product.
              */}
              {card.url && (
                <a
                  className="arow__source"
                  href={card.url}
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  <ExternalLink size={13} aria-hidden />
                  {source.label}’da aç
                  <span className="sr-only"> (yeni sekmede)</span>
                </a>
              )}

              {/*
                What pressing actually does, before it is pressed. The button
                above says "Jira kaydı aç", which sounds like a thing that
                happens on click; what happens is that these values are drafted
                and a person is asked. Printing the draft here — with the same
                Turkish field names the approval gate uses — is the difference
                between trusting the product and reading it.
              */}
              {primary && <ActionDraft action={primary} />}
              <div className="arow__actions">
                {others.map((action, i) => {
                  const thisBusy = busyTool === action.tool;
                  return (
                    <button
                      key={`${action.tool}-${i}`}
                      type="button"
                      className="btn btn--sm btn--outline"
                      disabled={busy}
                      onClick={() => onAction(card, action)}
                      title={action.tool}
                    >
                      {thisBusy && <Loader size={14} aria-hidden className="spin" />}
                      {thisBusy ? 'Başlatılıyor…' : actionLabel(action.tool, action.label)}
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

/** How much of a drafted value is worth printing before anyone has pressed anything. */
const DRAFT_VALUE_MAX = 140;

/**
 * The draft behind a suggestion: the tool, the values it is seeded with, and
 * whether pressing will stop for a signature.
 *
 * <p>The field names are the ones the approval gate uses, from `paramLabels`, so
 * the row a person reads now and the box they sign later say the same word for
 * the same thing. An unknown field keeps its raw name there and keeps it here.
 *
 * <p>`risk` comes from the server. Guessing it from the tool's name would make
 * this a promise about a write that the screen is in no position to make, so
 * when the field is missing the block says nothing about approval at all.
 */
function ActionDraft({ action }: { action: SuggestedAction }) {
  const fields = Object.entries(action.params ?? {}).filter(
    (entry): entry is [string, string | number] =>
      (typeof entry[1] === 'string' && entry[1].trim() !== '') || typeof entry[1] === 'number',
  );

  return (
    <div className="draft">
      <p className="draft__head">
        <span className="draft__label">Basınca ne olacak</span>
        <span className="draft__tool-wrap">
          {providerOf(action.tool) && <BrandMark provider={providerOf(action.tool)!} size={13} />}
          <code className="t-mono draft__tool">{action.tool}</code>
        </span>
      </p>

      {fields.length > 0 && (
        <dl className="draft__fields">
          {fields.map(([key, value]) => {
            const text = String(value);
            return (
              <div className="draft__field" key={key}>
                <dt>{paramLabel(key)}</dt>
                <dd>
                  {text.length > DRAFT_VALUE_MAX ? `${text.slice(0, DRAFT_VALUE_MAX)}…` : text}
                </dd>
              </div>
            );
          })}
        </dl>
      )}

      {action.risk === 'write' || action.risk === 'destructive' ? (
        <p className="draft__gate">
          <ShieldCheck size={13} aria-hidden />
          Yazma adımı — bu değerler ekranda önüne gelir, sen onaylamadan gönderilmez.
        </p>
      ) : action.risk === 'read' ? (
        <p className="draft__gate draft__gate--read">
          <ShieldCheck size={13} aria-hidden />
          Yalnız okuma — hiçbir yere bir şey yazılmaz.
        </p>
      ) : null}
    </div>
  );
}

type MeetingProps = {
  index: number;
  /** Does this row carry the screen's one filled button? */
  primary: boolean;
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
export function MeetingRow({ index, primary, title, detail, onPrepare, busy }: MeetingProps) {
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
            className={primaryClass(primary)}
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
  /** Does this row carry the screen's one filled button? */
  primary: boolean;
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
export function GapRow({ index, primary, status, provider, scope, reason, onAction }: GapProps) {
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

        <button type="button" className={primaryClass(primary)} onClick={onAction}>
          {failed ? 'Tekrar dene' : 'Bağlantılar’a git'}
        </button>
      </div>
    </motion.li>
  );
}
