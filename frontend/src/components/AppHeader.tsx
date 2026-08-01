import { Menu } from 'lucide-react';
import { useLayoutEffect, useRef } from 'react';
import type { RefObject } from 'react';
import type { Route } from '../lib/router';
import { ApprovalBadge } from './ApprovalBadge';
import '../styles/sidebar.css';

type Props = {
  route: Route;
  onNavigate: (hash: string) => void;
  /** Opens the navigation drawer — this bar carries no destinations of its own. */
  onOpenNav: () => void;
  navOpen: boolean;
  /** So closing the drawer can hand focus back to the control that opened it. */
  menuRef?: RefObject<HTMLButtonElement>;
};

/**
 * The top bar below 1024px — and only below it.
 *
 * <p>WHAT LEFT THIS FILE (#130). Six centred tabs. They were the product's second
 * navigation surface next to the run rail, which `avoid-mixed-patterns` forbids, and on a
 * phone they were a 560px strip inside a 364px rail that admitted it was cut off with a
 * mask (#71). The destinations now live in the sidebar; on a viewport too narrow for a
 * sidebar they live in the drawer this bar opens, which is what `adaptive-navigation`
 * asks for. Nothing was dropped — the same addresses are one press away.
 *
 * <p>WHAT STAYED. The brand, and the count of flows parked on a person. That count is the
 * one thing on screen that belongs to no screen in particular (#72), so it has to survive
 * on a bar that no longer navigates anywhere.
 *
 * <p>The element is `header.topbar`, not `header.header`, and that is load-bearing:
 * AccountMenu looks for `header.header` once, on mount, and portals into it. A host that
 * exists at one viewport width and not at another would leave the chip portalled into a
 * detached node the moment a window is dragged across 1024px. With no such host anywhere,
 * the chip takes its own fixed position and sidebar.css puts it where it belongs at each
 * width — one code path, no resize to get wrong.
 */
export function AppHeader({ route, onNavigate, onOpenNav, navOpen, menuRef }: Props) {
  const ref = useRef<HTMLElement>(null);

  /*
    The bar floats over the scrolling content, so the layout below has to know exactly how
    tall it is. Measured rather than assumed: a wrong guess hides the first line of the
    page. At >=1024px this component is not mounted at all, and the shell sets
    `--header-h` to zero instead.
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
    <header className="topbar" ref={ref}>
      <button
        type="button"
        className="topbar__menu"
        ref={menuRef}
        onClick={onOpenNav}
        aria-label="Gezinmeyi aç"
        aria-haspopup="dialog"
        aria-expanded={navOpen}
      >
        <Menu size={18} aria-hidden />
      </button>

      <button
        type="button"
        className="brand"
        onClick={() => onNavigate('#/')}
        aria-label="Relay ana ekran"
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
        <span className="brand__word" aria-hidden>
          <span className="brand__r">r</span>elay
        </span>
      </button>

      {/*
        A run parked on a person is the one thing in this product that costs something to
        miss (#72). It reads the same count as the sidebar's Akışlar badge — one hook, one
        request, one number (#100).
      */}
      <ApprovalBadge routeKey={route.name} onNavigate={onNavigate} />
    </header>
  );
}
