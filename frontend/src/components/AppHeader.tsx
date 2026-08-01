import { History, MessageSquare, Plug, Search, ShieldCheck, Sun } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useLayoutEffect, useRef } from 'react';
import type { Route } from '../lib/router';

type Props = {
  route: Route;
  onNavigate: (hash: string) => void;
};

const ITEMS: { hash: string; label: string; match: Route['name'][]; Icon: LucideIcon }[] = [
  { hash: '#/', label: 'Bugün', match: ['today'], Icon: Sun },
  { hash: '#/sor', label: 'Postana sor', match: ['ask'], Icon: Search },
  { hash: '#/sohbet', label: 'Sohbet', match: ['chat'], Icon: MessageSquare },
  { hash: '#/history', label: 'Geçmiş', match: ['history', 'history-detail'], Icon: History },
  { hash: '#/connections', label: 'Bağlantılar', match: ['connections'], Icon: Plug },
  { hash: '#/politikalar', label: 'Politikalar', match: ['policies'], Icon: ShieldCheck },
];

export function AppHeader({ route, onNavigate }: Props) {
  const ref = useRef<HTMLElement>(null);

  /*
    The bar floats over the scrolling content (that is what makes the frosted
    glass mean anything), so the layout below has to know exactly how tall it
    is. It is NOT a constant: below 640px the nav wraps onto its own row.
    Measuring beats guessing — a wrong guess hides the first line of content.
  */
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const apply = () => {
      const h = Math.round(el.getBoundingClientRect().height);
      if (h > 0) document.documentElement.style.setProperty('--header-h', `${h}px`);
    };
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <header className="header" ref={ref}>
      <button type="button" className="brand" onClick={() => onNavigate('#/')} aria-label="Relay ana ekran">
        <span className="brand__mark" aria-hidden>
          {/* bayrak devri: nokta -> cubuk -> nokta */}
          <svg viewBox="0 0 64 64" width="15" height="15">
            <circle cx="17" cy="38" r="7" fill="currentColor" />
            <rect x="20" y="23.5" width="24" height="8" rx="4" fill="currentColor" transform="rotate(-14 32 27.5)" />
            <circle cx="47" cy="20" r="7" fill="currentColor" />
          </svg>
        </span>
        <span className="brand__word" aria-hidden>
          <span className="brand__r">r</span>elay
        </span>
      </button>

      {/*
        Labels are ALWAYS rendered — an icon-only nav is an accessibility
        failure (issue #12). On narrow viewports the strip wraps to its own
        row and scrolls horizontally instead of dropping the text.
      */}
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
    </header>
  );
}
