import {
  BarChart3,
  ChevronsLeft,
  ChevronsRight,
  ListChecks,
  Plug,
  Plus,
  ShieldCheck,
  Sun,
  Users,
  X,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useAwaitingRuns } from '../lib/awaitingRuns';
import type { Route } from '../lib/router';
import '../styles/sidebar.css';

/**
 * Where the sidebar stops being the right pattern.
 *
 * <p>`adaptive-navigation`: below this width a permanent column is a quarter of the
 * viewport spent on furniture, so the same list becomes a drawer behind a top bar. The
 * number is the one the rule names, not one measured here — 1024 is where a laptop stops
 * being a laptop.
 */
const WIDE = '(min-width: 1024px)';

/** Remembered per browser: a rail someone collapsed must stay collapsed after a refresh. */
const COLLAPSE_KEY = 'relay.sidebar.collapsed';

function mediaQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia(WIDE);
}

/**
 * Is there room for a sidebar at all?
 *
 * <p>A media query rather than CSS alone, because the two layouts are not the same
 * markup: rendering both and hiding one would put two navigations in the accessibility
 * tree, which is the mixed-pattern bug in a different costume.
 */
export function useWideViewport(): boolean {
  const [wide, setWide] = useState<boolean>(() => mediaQuery()?.matches ?? true);

  useEffect(() => {
    const mq = mediaQuery();
    if (!mq) return;
    const apply = () => setWide(mq.matches);
    apply();
    mq.addEventListener?.('change', apply);
    return () => mq.removeEventListener?.('change', apply);
  }, []);

  return wide;
}

/** The collapsed state as the browser last left it. */
export function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSE_KEY) === '1';
  } catch {
    return false;
  }
}

export function writeCollapsed(collapsed: boolean): void {
  try {
    window.localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0');
  } catch {
    /* private mode, quota, a browser that says no — the rail still works */
  }
}

type NavItem = {
  hash: string;
  label: string;
  match: Route['name'][];
  Icon: LucideIcon;
  /** Only Akışlar carries one: the number of flows stopped on a person. */
  badge?: boolean;
};

/*
  The destinations, in the order the spec sets (scratchpad/sidebar-spec.md).

  Bugün is first because it is the screen the product opens on. Akışlar is the
  runs list — it holds the waiting count, so the badge is on the destination
  that can answer it. Ekip (#113) is a member list derived from the registered
  tools; Panel counts what already happened.

  Sohbet is deliberately NOT here. It is not a place, it is the flow you are in:
  `+ Yeni iş` starts one and the live rows below open the others. A tab for it
  would have been a seventh destination pointing at whichever run happened to be
  in memory.
*/
const PRIMARY: NavItem[] = [
  { hash: '#/', label: 'Bugün', match: ['today'], Icon: Sun },
  {
    hash: '#/history',
    label: 'Akışlar',
    match: ['history', 'history-detail'],
    Icon: ListChecks,
    badge: true,
  },
  { hash: '#/ekip', label: 'Ekip', match: ['crew'], Icon: Users },
  { hash: '#/panel', label: 'Panel', match: ['panel'], Icon: BarChart3 },
];

/** Settings, in every sense: read rarely, changed rarely, never during a demo. */
const SECONDARY: NavItem[] = [
  { hash: '#/connections', label: 'Bağlantılar', match: ['connections'], Icon: Plug },
  { hash: '#/politikalar', label: 'Politikalar', match: ['policies'], Icon: ShieldCheck },
];

type Props = {
  route: Route;
  onNavigate: (hash: string) => void;
  /** `rail` is the permanent column; `drawer` is the same list over the page below 1024px. */
  variant: 'rail' | 'drawer';
  /** Icon-only. Never true for the drawer — a drawer is opened to be read. */
  collapsed?: boolean;
  onToggleCollapse?: () => void;
  /** Drawer only. */
  onClose?: () => void;
};

/**
 * The one navigation surface: where you can go, and what is happening.
 *
 * <p>WHY THIS REPLACED THE TOP BAR (#130). The product had two navigations at the same
 * level — a centred strip of six tabs, and a rail of live runs that existed on one screen
 * out of seven. `avoid-mixed-patterns` is the rule that forbids the pair, and the strip is
 * the half that had to go: it was already scrolling sideways at 390px (#71) and had no room
 * for the seventh destination Ekip needs.
 *
 * <p>WHY THE COUNT IS NOT A PROP. The badge here and the count in the top bar must never
 * disagree — that exact bug was #100. Both read `useAwaitingRuns`, which is one request to
 * `GET /api/panel` counted in SQL. A number passed in from a caller is a second place it
 * could be computed, and a second place is how two surfaces start telling two stories.
 */
export function AppSidebar({
  route,
  onNavigate,
  variant,
  collapsed = false,
  onToggleCollapse,
  onClose,
}: Props) {
  const awaiting = useAwaitingRuns(route.name);
  const drawer = variant === 'drawer';
  // Icon-only is a rail affordance. A drawer that opened icon-only would be a drawer
  // holding a rail, which is nothing anybody asked to see.
  const tight = collapsed && !drawer;
  const ref = useRef<HTMLElement>(null);

  /*
    A drawer is modal, so the keyboard has to be able to get in, move around and get out.
    Focus goes to the first control on open; Escape closes; Tab wraps inside. Without the
    wrap, tabbing past the last item lands on the page behind a scrim that cannot be
    reached with a mouse — reachable in the tree, invisible on screen.
  */
  useEffect(() => {
    if (!drawer) return;
    const root = ref.current;
    if (!root) return;
    const focusable = () =>
      [...root.querySelectorAll<HTMLElement>('button, [href], [tabindex]:not([tabindex="-1"])')];
    focusable()[0]?.focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose?.();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !root.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawer, onClose]);

  const go = (hash: string) => {
    onNavigate(hash);
    onClose?.();
  };

  const item = (nav: NavItem) => {
    const current = nav.match.includes(route.name);
    const count = nav.badge && awaiting != null && awaiting > 0 ? awaiting : null;
    return (
      <li className="sb__li" key={nav.hash}>
        <button
          type="button"
          className={`sb__item${current ? ' sb__item--current' : ''}`}
          /*
            The tooltip is not decoration: an icon-only rail without one is a memory test
            (`nav-label-icon`). It is drawn from this attribute in CSS rather than by the
            browser's `title`, so it appears at once and on keyboard focus too — a native
            tooltip needs a mouse and a second of hovering.
          */
          data-tip={nav.label}
          aria-current={current ? 'page' : undefined}
          onClick={() => go(nav.hash)}
        >
          <nav.Icon size={17} aria-hidden />
          {/* Clipped, not removed, when the rail is tight: the label stays the button's
              accessible name at every width. `display: none` would take it out of the
              accessibility tree and leave a screen reader reading an icon. */}
          <span className="sb__label">{nav.label}</span>
          {count != null && (
            <span
              className="sb__badge"
              // Two digits on screen; the whole claim in the accessible name, the same
              // trade the top bar's badge makes.
              aria-label={`${count} akış onayını bekliyor`}
            >
              {count}
            </span>
          )}
        </button>
      </li>
    );
  };

  return (
    <aside
      ref={ref}
      className={`sb${tight ? ' sb--tight' : ''}${drawer ? ' sb--drawer' : ''}`}
      role={drawer ? 'dialog' : undefined}
      aria-modal={drawer ? true : undefined}
      aria-label={drawer ? 'Gezinme' : undefined}
    >
      <div className="sb__head">
        <button
          type="button"
          className="brand sb__brand"
          onClick={() => go('#/')}
          aria-label="Relay ana ekran"
          data-tip="Relay"
        >
          <span className="brand__mark" aria-hidden>
            {/* bayrak devri: nokta -> cubuk -> nokta */}
            <svg viewBox="0 0 64 64" width="15" height="15">
              <circle cx="17" cy="38" r="7" fill="currentColor" />
              <rect
                x="20"
                y="23.5"
                width="24"
                height="8"
                rx="4"
                fill="currentColor"
                transform="rotate(-14 32 27.5)"
              />
              <circle cx="47" cy="20" r="7" fill="currentColor" />
            </svg>
          </span>
          <span className="brand__word sb__label" aria-hidden>
            <span className="brand__r">r</span>elay
          </span>
        </button>

        {drawer ? (
          <button type="button" className="sb__icon-btn" onClick={onClose} aria-label="Gezinmeyi kapat">
            <X size={18} aria-hidden />
          </button>
        ) : (
          <button
            type="button"
            className="sb__icon-btn"
            onClick={onToggleCollapse}
            aria-label={tight ? 'Kenar çubuğunu genişlet' : 'Kenar çubuğunu daralt'}
            data-tip={tight ? 'Genişlet' : 'Daralt'}
            aria-expanded={!tight}
          >
            {tight ? <ChevronsRight size={16} aria-hidden /> : <ChevronsLeft size={16} aria-hidden />}
          </button>
        )}
      </div>

      {/*
        The one primary action, alone above the destinations. It lands on `#/sohbet` with
        no run named — the screen there reads that as "start a new one".
      */}
      <button type="button" className="sb__new" onClick={() => go('#/sohbet')} data-tip="Yeni iş">
        <Plus size={17} aria-hidden />
        <span className="sb__label">Yeni iş</span>
      </button>

      <nav className="sb__nav" aria-label="Ana gezinme">
        <ul className="sb__list">{PRIMARY.map(item)}</ul>
        {/* `nav-hierarchy`: the rule is the whole difference between "where the work is"
            and "how the work is allowed to happen". */}
        <hr className="sb__rule" />
        <ul className="sb__list">{SECONDARY.map(item)}</ul>
      </nav>

      <div className="sb__spacer" />

      {/*
        Who is signed in sits at the bottom, and it is AccountMenu that draws it — a
        component this file does not own and does not need to. It pins itself to the
        viewport, so sidebar.css parks it over this row; the row exists to reserve the
        space, so the list above never scrolls underneath the chip.
      */}
      <div className="sb__foot" aria-hidden />
    </aside>
  );
}
