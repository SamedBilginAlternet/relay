import { useEffect, useRef } from 'react';
import { AppHeader } from './components/AppHeader';
import { useHashRoute } from './lib/router';
import { AskScreen } from './screens/AskScreen';
import { AuthGate } from './screens/AuthGate';
import { ChatScreen } from './screens/ChatScreen';
import { ConnectionsScreen } from './screens/ConnectionsScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { RunDetailScreen } from './screens/RunDetailScreen';
import { TodayScreen } from './screens/TodayScreen';

export default function App() {
  const [route, navigate] = useHashRoute();
  const mainRef = useRef<HTMLElement>(null);
  const first = useRef(true);

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
      <div className="app">
        <a className="skip-link" href="#main">
          İçeriğe geç
        </a>
        <AppHeader route={route} onNavigate={navigate} />
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
        </main>
      </div>
    </AuthGate>
  );
}
