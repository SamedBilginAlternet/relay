import { ExternalLink, Plug, TriangleAlert } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { BriefSection } from '../types/brief';

type Props = {
  title: string;
  Icon: LucideIcon;
  section: BriefSection;
  /** What the user has to connect for this section to work. */
  connectLabel: string;
  emptyText: string;
  loading?: boolean;
  onGoToConnections: () => void;
  onRetry: () => void;
};

/**
 * NEVER-BLANK rule. Four outcomes, four explicit renders:
 * loading → skeleton, ok+items → list, ok+empty → friendly line,
 * unavailable → what to connect + a way there, error → reason + retry.
 */
export function BriefSectionCard({
  title,
  Icon,
  section,
  connectLabel,
  emptyText,
  loading = false,
  onGoToConnections,
  onRetry,
}: Props) {
  const count = section.status === 'ok' ? section.items.length : null;

  return (
    <section className="brief-card" aria-busy={loading || undefined}>
      <header className="brief-card__head">
        <span className="brief-card__icon" aria-hidden>
          <Icon size={15} />
        </span>
        <h2 className="brief-card__title">{title}</h2>
        {count != null && <span className="brief-card__count">{count}</span>}
      </header>

      {loading && (
        <div className="brief-card__body">
          <div className="skeleton" style={{ height: 34 }} />
          <div className="skeleton" style={{ height: 34, opacity: 0.7 }} />
          <div className="skeleton" style={{ height: 34, opacity: 0.4 }} />
        </div>
      )}

      {!loading && section.status === 'ok' && section.items.length > 0 && (
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

      {!loading && section.status === 'ok' && section.items.length === 0 && (
        <p className="brief-card__quiet">{emptyText}</p>
      )}

      {!loading && section.status === 'unavailable' && (
        <div className="brief-card__state">
          <p className="brief-card__state-title">
            <Plug size={14} aria-hidden />
            {connectLabel} bağlı değil
          </p>
          <p className="t-caption">
            {section.reason ??
              `${connectLabel} bağlantısı kurulmadığı için bu bölüm boş. Diğer bölümler etkilenmedi.`}
          </p>
          <button type="button" className="btn btn--outline btn--sm" onClick={onGoToConnections}>
            Bağlantılar’a git
          </button>
        </div>
      )}

      {!loading && section.status === 'error' && (
        <div className="brief-card__state brief-card__state--error" role="alert">
          <p className="brief-card__state-title">
            <TriangleAlert size={14} aria-hidden />
            {connectLabel} yanıt vermedi
          </p>
          <p className="t-caption">
            {section.reason ?? 'Bu bölüm çekilirken hata oldu. Diğer bölümler etkilenmedi.'}
          </p>
          <button type="button" className="btn btn--outline btn--sm" onClick={onRetry}>
            Tekrar dene
          </button>
        </div>
      )}
    </section>
  );
}
