import { BarChart3, History, MessageSquare, Plug, ShieldCheck, Sun } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { Route } from '../lib/router';
import '../styles/screens.css';

type Props = {
  route: Route;
  onNavigate: (hash: string) => void;
};

/*
  The top bar is the product's table of contents, so what is on it is a claim
  about what the product is: it finishes work in the tools, writes, and asks
  before it writes.

  `Postana sor` (`#/sor`) does none of those three — its own copy says "Okur,
  yazmaz" — and it sat second, ahead of the screens the pitch is actually
  about. Seven destinations is more than three minutes can carry, and a tab
  nobody demonstrates still gets clicked. So it comes off the bar; the route,
  the screen and `POST /api/ask` all stay exactly where they are, and the
  account menu keeps a way in. Removing it from the map, not from the product.
*/
const ITEMS: { hash: string; label: string; match: Route['name'][]; Icon: LucideIcon }[] = [
  { hash: '#/', label: 'Bugün', match: ['today'], Icon: Sun },
  { hash: '#/sohbet', label: 'Sohbet', match: ['chat'], Icon: MessageSquare },
  { hash: '#/history', label: 'Geçmiş', match: ['history', 'history-detail'], Icon: History },
  { hash: '#/connections', label: 'Bağlantılar', match: ['connections'], Icon: Plug },
  { hash: '#/politikalar', label: 'Politikalar', match: ['policies'], Icon: ShieldCheck },
  { hash: '#/panel', label: 'Panel', match: ['panel'], Icon: BarChart3 },
];

export function AppHeader({ route, onNavigate }: Props) {
  const ref = useRef<HTMLElement>(null);
  const navRef = useRef<HTMLElement>(null);
  /*
    Which sides of the strip have destinations behind them. Below 960px the nav
    scrolls sideways, and it did so with no sign at all: at 390px two of six
    tabs were off-screen and the only clue was a word clipped by the viewport
    edge. There is no `:hover` on a phone, so the strip has to say it itself.
  */
  const [cut, setCut] = useState({ start: false, end: false });

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

  useEffect(() => {
    const nav = navRef.current;
    if (!nav) return;
    const measure = () => {
      const slack = nav.scrollWidth - nav.clientWidth;
      setCut({
        start: nav.scrollLeft > 1,
        // 1px of tolerance: fractional layout widths otherwise leave a fade
        // switched on over a strip that has nothing left to show.
        end: slack > 1 && nav.scrollLeft < slack - 1,
      });
    };
    measure();
    nav.addEventListener('scroll', measure, { passive: true });
    const ro = new ResizeObserver(measure);
    ro.observe(nav);
    return () => {
      nav.removeEventListener('scroll', measure);
      ro.disconnect();
    };
  }, []);

  /*
    Land on Panel from a link and the tab for it was off-screen: the bar
    claimed you were nowhere. Scroll it into the rail by hand rather than with
    `scrollIntoView`, which would also scroll the page vertically to reach a
    bar that is already fixed at the top of it.
  */
  useLayoutEffect(() => {
    const nav = navRef.current;
    const active = nav?.querySelector<HTMLElement>('[aria-current="page"]');
    if (!nav || !active) return;
    const left = active.offsetLeft;
    const right = left + active.offsetWidth;
    const pad = 16;
    if (left - pad < nav.scrollLeft) nav.scrollLeft = Math.max(0, left - pad);
    else if (right + pad > nav.scrollLeft + nav.clientWidth) {
      nav.scrollLeft = right + pad - nav.clientWidth;
    }
  }, [route.name]);

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
      <nav
        className={`nav${cut.start ? ' nav--cut-start' : ''}${cut.end ? ' nav--cut-end' : ''}`}
        aria-label="Ana gezinme"
        ref={navRef}
      >
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
