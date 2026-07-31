import { History, MessageSquare, Plug } from 'lucide-react';
import { RUN_SOURCE_KIND } from '../data';
import type { Route } from '../lib/router';

type Props = {
  route: Route;
  onNavigate: (hash: string) => void;
};

const ITEMS: { hash: string; label: string; match: Route['name'][]; Icon: typeof MessageSquare }[] = [
  { hash: '#/', label: 'Sohbet', match: ['chat'], Icon: MessageSquare },
  { hash: '#/history', label: 'Geçmiş', match: ['history', 'history-detail'], Icon: History },
  { hash: '#/connections', label: 'Bağlantılar', match: ['connections'], Icon: Plug },
];

export function AppHeader({ route, onNavigate }: Props) {
  return (
    <header className="header">
      <button type="button" className="brand" onClick={() => onNavigate('#/')} aria-label="Relay ana ekran">
        <span className="brand__mark" aria-hidden>
          {/* bayrak devri: nokta -> cubuk -> nokta */}
          <svg viewBox="0 0 64 64" width="15" height="15">
            <circle cx="17" cy="38" r="7" fill="currentColor" />
            <rect x="20" y="23.5" width="24" height="8" rx="4" fill="currentColor" transform="rotate(-14 32 27.5)" />
            <circle cx="47" cy="20" r="7" fill="currentColor" />
          </svg>
        </span>
        <span aria-hidden>
          <span className="brand__r">r</span>elay
        </span>
      </button>

      <nav className="nav" aria-label="Ana gezinme">
        {ITEMS.map((item) => {
          const active = item.match.includes(route.name);
          return (
            <button
              key={item.hash}
              type="button"
              className="nav__item"
              aria-current={active ? 'page' : undefined}
              onClick={() => onNavigate(item.hash)}
            >
              <item.Icon size={15} aria-hidden />
              <span className="nav__label">{item.label}</span>
            </button>
          );
        })}
      </nav>

      <div className="header__right">
        <span
          className={`source-chip source-chip--${RUN_SOURCE_KIND}`}
          title={
            RUN_SOURCE_KIND === 'mock'
              ? 'Veri kaynağı: senaryo (mock). VITE_RUN_SOURCE=api ile gerçek backend’e geçilir.'
              : 'Veri kaynağı: canlı API'
          }
        >
          {RUN_SOURCE_KIND === 'mock' ? 'Demo veri' : 'Canlı API'}
        </span>
      </div>
    </header>
  );
}
