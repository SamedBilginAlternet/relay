import {
  Activity,
  BarChart3,
  ChevronRight,
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
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRunCounts } from '../lib/awaitingRuns';
import type { Route } from '../lib/router';
import { useRunStore } from '../store/runStore';
import { TaskRail, useLiveRuns } from './TaskRail';
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

/** Same, for the live list — which starts closed. See `readLiveOpen`. */
const LIVE_KEY = 'relay.sidebar.live';

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

/**
 * Is the live list open? **Closed unless it was left open.**
 *
 * <p>It shipped open, and on the live box that is 27 rows standing under the destinations
 * — a wall of text where a navigation should be. The count stays on the section's own row
 * either way, so what closing costs is the detail, not the news.
 */
export function readLiveOpen(): boolean {
  try {
    return window.localStorage.getItem(LIVE_KEY) === '1';
  } catch {
    return false;
  }
}

export function writeLiveOpen(open: boolean): void {
  try {
    window.localStorage.setItem(LIVE_KEY, open ? '1' : '0');
  } catch {
    /* the list still opens and closes; it just forgets between visits */
  }
}

type NavItem = {
  hash: string;
  label: string;
  match: Route['name'][];
  Icon: LucideIcon;
};

/*
  The destinations, in the order the spec sets (scratchpad/sidebar-spec.md).

  Bugün is first because it is the screen the product opens on. Akışlar is the
  runs list, and the screen that answers "what is waiting on me" once you are
  there. Ekip (#113) is a member list derived from the registered tools; Panel
  counts what already happened.

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
 * <p>WHY NO DESTINATION CARRIES A COUNT. Akışlar wore an amber badge of the flows parked
 * on a person, and it was read once and then stopped being read: a number that never
 * leaves the corner of the eye is furniture, not news. What it counted is still counted —
 * the top bar prints it below 1024px (`ApprovalBadge`) and the Akışlar screen names the
 * same set on its `Onay bekleyen` tab. Only this row lost it.
 *
 * <p>WHY THE NUMBERS ARE NOT PROPS. Every surface that prints one reads it from the hook,
 * which is one `GET /api/panel` counted in SQL. A number passed in from a caller is a
 * second place it could be computed, and a second place is how two surfaces started
 * telling two stories in #100.
 */
export function AppSidebar({
  route,
  onNavigate,
  variant,
  collapsed = false,
  onToggleCollapse,
  onClose,
}: Props) {
  const counts = useRunCounts(route.name);
  /*
    The live runs, on every screen (#130). The rail used to be part of Sohbet, which meant
    the flows stopped on a decision were visible from the one screen you had to already be
    on to see them. It is the same rail, the same hook and the same waiting-first order —
    it just belongs to the app now rather than to one of its screens.
  */
  const openRun = useRunStore((s) => s.run);
  const [liveOpen, setLiveOpen] = useState(readLiveOpen);
  /*
    The rows are fetched only when the list is open. Closed — which is how it starts —
    the section prints one integer, and that integer is counted in SQL by the
    `/api/panel` request this component already makes. Fetching three pages of two
    hundred run summaries to render it was six hundred rows of JSON per navigation and
    per minute, for a number that was already in the response (#139).
  */
  const liveRuns = useLiveRuns(openRun, liveOpen);
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

  const go = useCallback(
    (hash: string) => {
      onNavigate(hash);
      onClose?.();
    },
    [onNavigate, onClose],
  );

  const openFromRail = useCallback((runId: string) => go(`#/sohbet/${runId}`), [go]);

  const toggleLive = useCallback(() => {
    setLiveOpen((was) => {
      writeLiveOpen(!was);
      return !was;
    });
  }, []);

  /*
    Which row is the open one is a question about the ADDRESS, not about the store. The
    store keeps the last run it loaded for as long as the tab lives; marking that row
    current from Panel or Politikalar would claim a flow is on screen when the screen is
    something else entirely.
  */
  const currentRunId = route.name === 'chat' ? (route.runId ?? null) : null;

  const item = (nav: NavItem) => {
    const current = nav.match.includes(route.name);
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

        A row, not a filled button. The spec drew a solid violet block here and it was
        wrong on screen: it is the loudest thing in the product, sitting above six quiet
        rows, for an action taken once a session. Every sidebar this one is modelled on
        (Claude, Codex, Gemini) writes "new" as the first row of the list and lets the
        accent say "primary" — which is also the rule this file already keeps everywhere
        else: violet means state, not decoration.
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

      {/*
        Never rendered empty. An empty list under a heading is furniture: it says the same
        thing as no section at all and costs a rule and a line to say it.

        A disclosure, and closed by default. Open, it is 27 rows under the navigation on
        the live box — the column stops reading as a column. The number stays on the
        header at every state, so the closed section still answers "is anything running";
        opening it answers "which ones", which is a question you ask on purpose.

        The count here is every live flow, not only the ones stopped on you — the group
        heads inside the list name that split. It is the only number left in this column,
        which is also why it is grey: amber in this product means "waiting on you", and
        this is not that number.
      */}
      {(counts?.live ?? 0) > 0 && (
        <div className={`sb__live${liveOpen ? ' sb__live--open' : ''}`}>
          <hr className="sb__rule" />
          <button
            type="button"
            className="sb__section"
            aria-expanded={liveOpen}
            aria-controls="sb-live-list"
            onClick={toggleLive}
            data-tip="Açık işler"
          >
            {tight ? (
              <Activity size={17} aria-hidden />
            ) : (
              <ChevronRight size={14} aria-hidden className="sb__chev" />
            )}
            {/* "Açık işler", not "Canlı akışlar". `Akışlar` is a destination three rows
                above, and when it carried a count of its own the shared noun made the two
                numbers unreadable as a pair: the reader could not tell whether one was a
                subset of the other. The badge is gone, the noun stays split — it is what
                keeps this row a section head rather than a second Akışlar (#136). */}
            <span className="sb__label">Açık işler</span>
            {/* One number from one place. Counting the rows here instead would be a
                second definition of "live", and two definitions of one word is how two
                surfaces started telling two stories in #100. */}
            <span className={tight ? 'sb__badge sb__badge--live' : 'sb__count t-mono'}>
              {counts?.live ?? 0}
            </span>
          </button>
          {liveOpen && (
            <div className="sb__livelist" id="sb-live-list">
              <TaskRail
                runs={liveRuns}
                currentRunId={currentRunId}
                onOpen={openFromRail}
                tight={tight}
              />
            </div>
          )}
        </div>
      )}

      {/*
        Below the list, not above it: closed, this is what holds the account down at the
        bottom; open, it is not rendered at all and the list takes the room instead. The
        section's own top edge does not move between the two, so nothing jumps when it
        opens.
      */}
      {!(liveOpen && (counts?.live ?? 0) > 0) && <div className="sb__spacer" />}

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
