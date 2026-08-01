import { motion, useReducedMotion } from 'motion/react';
import { ChevronDown, ExternalLink, Play, Plug, TriangleAlert, Workflow, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { Playbook } from '../data/PlaybookSource';
import { enterProps } from '../lib/motion';
import type { BriefSection } from '../types/brief';

export type SectionMeta = {
  key: 'inbox' | 'work' | 'code' | 'calendar';
  title: string;
  Icon: LucideIcon;
  /** What the user has to connect for this section to work. */
  connectLabel: string;
  emptyText: string;
};

type TileProps = {
  meta: SectionMeta;
  section: BriefSection;
  loading: boolean;
  open: boolean;
  index: number;
  panelId: string;
  tileId: string;
  onToggle: () => void;
};

/**
 * NEVER-BLANK rule, compacted. The tile answers "how much is waiting here"
 * in one glance (count + the two newest lines); the full list is one click
 * away in the panel below the strip. Four outcomes still render explicitly:
 * loading → skeleton, ok+items → count + preview, ok+empty → friendly line,
 * unavailable/error → what is wrong, right on the tile.
 */
export function SectionTile({
  meta,
  section,
  loading,
  open,
  index,
  panelId,
  tileId,
  onToggle,
}: TileProps) {
  const reduce = useReducedMotion();
  const ok = section.status === 'ok';
  const preview = ok ? section.items.slice(0, 2) : [];

  return (
    <motion.div className="tile-wrap" {...enterProps(index, reduce)}>
      <button
        type="button"
        id={tileId}
        className={`tile tile--${section.status} ${open ? 'tile--open' : ''}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={onToggle}
        disabled={loading}
      >
        <span className="tile__head">
          <span className="tile__icon" aria-hidden>
            <meta.Icon size={15} />
          </span>
          <h3 className="tile__title">{meta.title}</h3>
          {/* "0" while loading would be a lie — an unknown count shows as a dash. */}
          {ok && !loading ? (
            <span className="tile__count">{section.items.length}</span>
          ) : (
            <span className="tile__count tile__count--off" aria-hidden>
              —
            </span>
          )}
        </span>

        {loading && (
          <span className="tile__lines">
            <span className="skeleton" style={{ height: 12 }} />
            <span className="skeleton" style={{ height: 12, opacity: 0.6 }} />
          </span>
        )}

        {!loading && ok && preview.length > 0 && (
          <span className="tile__lines">
            {preview.map((item) => (
              <span key={item.id} className="tile__line">
                <span className={`tile__dot tile__dot--${item.tone ?? 'default'}`} aria-hidden />
                {item.title}
              </span>
            ))}
          </span>
        )}

        {!loading && ok && preview.length === 0 && (
          <span className="tile__lines">
            <span className="tile__quiet">{meta.emptyText}</span>
          </span>
        )}

        {!loading && section.status === 'unavailable' && (
          <span className="tile__lines">
            <span className="tile__quiet tile__quiet--warn">
              <Plug size={13} aria-hidden /> {meta.connectLabel} bağlı değil
            </span>
          </span>
        )}

        {!loading && section.status === 'error' && (
          <span className="tile__lines">
            <span className="tile__quiet tile__quiet--danger">
              <TriangleAlert size={13} aria-hidden /> {meta.connectLabel} yanıt vermedi
            </span>
          </span>
        )}

        <span className="tile__more">
          {open ? 'Kapat' : 'Tümü'}
          <ChevronDown size={14} aria-hidden className="tile__chev" />
        </span>
      </button>
    </motion.div>
  );
}

type PanelProps = {
  meta: SectionMeta;
  section: BriefSection;
  panelId: string;
  tileId: string;
  /** Row to bring into view and mark — set when the panel was opened by name
   *  from the day summary rather than by clicking the tile. */
  focusItemId?: string | null;
  onClose: () => void;
  onGoToConnections: () => void;
  onRetry: () => void;
};

/** The full list for exactly one section — never two at once, so the screen
 *  keeps its height and the user keeps their place. */
export function SectionPanel({
  meta,
  section,
  panelId,
  tileId,
  focusItemId,
  onClose,
  onGoToConnections,
  onRetry,
}: PanelProps) {
  const focusRef = useRef<HTMLLIElement>(null);
  const reduce = useReducedMotion();

  /* The panel is still expanding when this runs, so its rows have no final
     position yet — wait out the expansion, then bring the named row in. The
     mark itself is not a flash: it stays until the panel is closed, so it is
     still there for someone who took a second to look away. */
  useEffect(() => {
    if (!focusItemId) return;
    const id = window.setTimeout(() => {
      focusRef.current?.scrollIntoView({
        behavior: reduce ? 'auto' : 'smooth',
        block: 'center',
      });
    }, 260);
    return () => window.clearTimeout(id);
  }, [focusItemId, reduce]);

  return (
    <div className="tpanel" id={panelId} role="region" aria-labelledby={tileId}>
      <div className="tpanel__head">
        <span className="t-label">{meta.title}</span>
        {section.status === 'ok' && (
          <span className="t-caption">{section.items.length} kayıt</span>
        )}
        <button type="button" className="btn btn--ghost btn--sm tpanel__close" onClick={onClose}>
          <X size={14} aria-hidden />
          Kapat
        </button>
      </div>

      {section.status === 'ok' && section.items.length > 0 && (
        <ul className="brief-list">
          {section.items.map((item) => {
            const focused = focusItemId != null && item.id === focusItemId;
            return (
            <li
              key={item.id}
              ref={focused ? focusRef : undefined}
              className={`brief-row${focused ? ' brief-row--focus' : ''}`}
            >
              <span className={`brief-row__dot brief-row__dot--${item.tone ?? 'default'}`} aria-hidden />
              <span className="brief-row__main">
                <span className="brief-row__title">
                  {item.url ? (
                    <a href={item.url} target="_blank" rel="noreferrer" className="brief-row__link">
                      {item.title}
                      <ExternalLink size={12} aria-hidden />
                    </a>
                  ) : (
                    item.title
                  )}
                </span>
                {item.subtitle && <span className="brief-row__sub">{item.subtitle}</span>}
              </span>
              {item.meta && <span className="brief-row__meta">{item.meta}</span>}
            </li>
            );
          })}
        </ul>
      )}

      {section.status === 'ok' && section.items.length === 0 && (
        <p className="brief-card__quiet">{meta.emptyText}</p>
      )}

      {section.status === 'unavailable' && (
        <div className="brief-card__state">
          <p className="brief-card__state-title">
            <Plug size={14} aria-hidden />
            {meta.connectLabel} bağlı değil
          </p>
          <p className="t-caption">
            {section.reason ??
              `${meta.connectLabel} bağlantısı kurulmadığı için bu bölüm boş. Diğer bölümler etkilenmedi.`}
          </p>
          <button type="button" className="btn btn--outline btn--sm" onClick={onGoToConnections}>
            Bağlantılar’a git
          </button>
        </div>
      )}

      {section.status === 'error' && (
        <div className="brief-card__state brief-card__state--error" role="alert">
          <p className="brief-card__state-title">
            <TriangleAlert size={14} aria-hidden />
            {meta.connectLabel} yanıt vermedi
          </p>
          <p className="t-caption">
            {section.reason ?? 'Bu bölüm çekilirken hata oldu. Diğer bölümler etkilenmedi.'}
          </p>
          <button type="button" className="btn btn--outline btn--sm" onClick={onRetry}>
            Tekrar dene
          </button>
        </div>
      )}
    </div>
  );
}

const PROVIDER_LABEL: Record<string, string> = {
  jira: 'Jira',
  github: 'GitHub',
  slack: 'Slack',
  google: 'Google',
};

function missingText(missing: string[]): string {
  const names = missing.map((p) => PROVIDER_LABEL[p] ?? p);
  return `${names.join(', ')} bağlı değil`;
}

type ShelfProps = {
  playbooks: Playbook[];
  loading: boolean;
  error: string | null;
  /** Id of the flow being started, so only that pill says so. */
  starting: string | null;
  onRun: (id: string) => void;
};

/**
 * The written-down flows, kept within reach (issue #15).
 *
 * They used to live in the onboarding tour and nowhere else, which meant they
 * disappeared the moment the tour was finished — the flows the product is built
 * around were reachable exactly once per account.
 *
 * The shape is a *shelf*, not a card grid, and that is the whole point: Bugün
 * had just been cut from 2680px to 844px and a second grid would have handed
 * that back. One wrapping row of pills, label included, costs a single 44px
 * line. A flow whose provider is missing is not hidden and not merely greyed
 * out — greying it out would answer "no" without answering "why" — it carries
 * the missing connection on a second line inside the same 44px.
 */
export function PlaybookShelf({ playbooks, loading, error, starting, onRun }: ShelfProps) {
  // Nothing to shelve and nothing to explain: no empty furniture on the screen.
  if (!loading && !error && playbooks.length === 0) return null;

  return (
    <section className="shelf" aria-labelledby="shelf-h">
      <h2 className="t-label shelf__label" id="shelf-h">
        <Workflow size={12} aria-hidden /> Hazır akışlar
      </h2>

      {loading && <span className="shelf__note">Yükleniyor…</span>}
      {error && (
        <span className="shelf__note shelf__note--warn">
          <TriangleAlert size={13} aria-hidden />
          {error}
        </span>
      )}

      {playbooks.map((playbook) => {
        const blocked = !playbook.runnable;
        const busy = starting === playbook.id;
        return (
          <button
            key={playbook.id}
            type="button"
            className={`shelf__item${blocked ? ' shelf__item--off' : ''}`}
            disabled={blocked || starting !== null}
            onClick={() => onRun(playbook.id)}
          >
            <span className="shelf__icon" aria-hidden>
              {blocked ? <Plug size={14} /> : <Play size={13} />}
            </span>
            <span className="shelf__text">
              <span className="shelf__title">{playbook.title}</span>
              {busy && <span className="shelf__sub">Başlatılıyor…</span>}
              {!busy && blocked && (
                <span className="shelf__sub shelf__sub--warn">{missingText(playbook.missing)}</span>
              )}
            </span>
            {/* The steps are what makes a playbook worth trusting; they do not fit
                on a pill, but they belong in the accessible name. */}
            <span className="sr-only">
              {blocked
                ? `Çalıştırılamaz. ${playbook.subtitle}`
                : `Çalıştır. ${playbook.subtitle}`}
            </span>
          </button>
        );
      })}
    </section>
  );
}
