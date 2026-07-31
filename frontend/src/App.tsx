import { AppHeader } from './components/AppHeader';
import { useHashRoute } from './lib/router';
import { ChatScreen } from './screens/ChatScreen';
import { ConnectionsScreen } from './screens/ConnectionsScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { RunDetailScreen } from './screens/RunDetailScreen';

export default function App() {
  const [route, navigate] = useHashRoute();

  return (
    <div className="app">
      <AppHeader route={route} onNavigate={navigate} />
      <main className="main">
        {route.name === 'chat' && <ChatScreen />}
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
  );
}
