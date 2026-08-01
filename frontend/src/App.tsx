import { useCallback, useEffect, useRef, useState } from 'react';
import { AppHeader } from './components/AppHeader';
import { AppSidebar, readCollapsed, useWideViewport, writeCollapsed } from './components/AppSidebar';
import { PointerHalo } from './components/PointerHalo';
import { useHashRoute } from './lib/router';
import { AskScreen } from './screens/AskScreen';
import { AuthGate } from './screens/AuthGate';
import { ChatScreen } from './screens/ChatScreen';
import { ConnectionsScreen } from './screens/ConnectionsScreen';
import { CrewScreen } from './screens/CrewScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { PanelScreen } from './screens/PanelScreen';
import { PolicyScreen } from './screens/PolicyScreen';
import { RunDetailScreen } from './screens/RunDetailScreen';
import { TodayScreen } from './screens/TodayScreen';

export default function App() {
  const [route, navigate] = useHashRoute();
  const mainRef = useRef<HTMLElement>(null);
  const first = useRef(true);

  /*
    One navigation surface, two shapes (#130). At >=1024px it is the permanent sidebar;
    below that a sidebar would be a quarter of the viewport spent on furniture, so the
    same component becomes a drawer behind the top bar. Rendering both and hiding one in
    CSS would put two navigations in the accessibility tree — the mixed-pattern bug this
    refactor exists to remove, wearing a different costume.
  */
  const wide = useWideViewport();
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const [navOpen, setNavOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);

  const toggleCollapse = useCallback(() => {
    setCollapsed((was) => {
      writeCollapsed(!was);
      return !was;
    });
  }, []);

  // Closing hands the keyboard back to the button that opened it; anything else drops
  // focus on <body> and the next Tab starts the page again from the top.
  const closeNav = useCallback(() => {
    setNavOpen(false);
    menuRef.current?.focus();
  }, []);

  // A viewport that grows past the breakpoint has a sidebar again, and a drawer left open
  // over it would be a second copy of the same list.
  useEffect(() => {
    if (wide) setNavOpen(false);
  }, [wide]);

  /*
    The shell tells the document which navigation is on screen. The account chip is drawn
    by AccountMenu, which pins itself to the viewport from outside this tree, and the
    width of the sidebar is what decides where it belongs — so that fact has to be
    readable from the root element by CSS alone.
  */
  useEffect(() => {
    const mode = wide ? (collapsed ? 'tight' : 'wide') : 'off';
    document.documentElement.dataset.sidebar = mode;
    return () => {
      delete document.documentElement.dataset.sidebar;
    };
  }, [wide, collapsed]);

  // Screen readers must land in the new screen after a route change.
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    mainRef.current?.focus({ preventScroll: true });
  }, [route.name]);

  return (
    // Nothing below renders until the session is known: signed out lands on #/giris,
    // a first-time account lands in the onboarding tour.
    <AuthGate route={route} onNavigate={navigate}>
      <div className={`app${wide ? ' app--sidebar' : ''}${wide && collapsed ? ' app--tight' : ''}`}>
        {/* Decoration, in this order on purpose: the wash sits behind everything
            (z-index -1) and the halo above it, and neither takes a pointer event. */}
        <div className="aurora" aria-hidden />
        <PointerHalo />
        <a className="skip-link" href="#main">
          İçeriğe geç
        </a>

        {wide ? (
          <AppSidebar
            route={route}
            onNavigate={navigate}
            variant="rail"
            collapsed={collapsed}
            onToggleCollapse={toggleCollapse}
          />
        ) : (
          <>
            <AppHeader
              route={route}
              onNavigate={navigate}
              onOpenNav={() => setNavOpen(true)}
              navOpen={navOpen}
              menuRef={menuRef}
            />
            {navOpen && (
              <div className="sb-drawer">
                {/* The scrim is a button so a pointer can dismiss the drawer; Escape and
                    the close control do the same job for a keyboard, and it is out of the
                    tab order so the drawer's own controls are what Tab finds. */}
                <button
                  type="button"
                  className="sb-drawer__scrim"
                  aria-label="Gezinmeyi kapat"
                  tabIndex={-1}
                  onClick={closeNav}
                />
                <AppSidebar route={route} onNavigate={navigate} variant="drawer" onClose={closeNav} />
              </div>
            )}
          </>
        )}

        <main className="main" id="main" ref={mainRef} tabIndex={-1}>
          {route.name === 'today' && <TodayScreen onNavigate={navigate} />}
          {route.name === 'chat' && <ChatScreen />}
          {route.name === 'ask' && <AskScreen />}
          {route.name === 'history' && <HistoryScreen onOpen={(id) => navigate(`#/history/${id}`)} />}
          {route.name === 'history-detail' && (
            <RunDetailScreen
              runId={route.runId}
              onBack={() => navigate('#/history')}
              onNavigate={navigate}
            />
          )}
          {route.name === 'connections' && <ConnectionsScreen />}
          {route.name === 'policies' && <PolicyScreen />}
          {route.name === 'panel' && <PanelScreen />}
          {/* Ekip (#113) landed while this was being built — the nav item and the screen
              were two agents' work meeting at one address. */}
          {route.name === 'crew' && <CrewScreen />}
        </main>
      </div>
    </AuthGate>
  );
}
