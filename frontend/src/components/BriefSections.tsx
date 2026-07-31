import { motion, useReducedMotion } from 'motion/react';
import { ChevronDown, ExternalLink, Plug, TriangleAlert, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
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
  onClose,
  onGoToConnections,
  onRetry,
}: PanelProps) {
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
          {section.items.map((item) => (
            <li key={item.id} className="brief-row">
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
          ))}
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
