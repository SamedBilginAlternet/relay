import { useEffect, useState } from 'react';

export type Route =
  | { name: 'today' }
  | { name: 'chat' }
  | { name: 'history' }
  | { name: 'history-detail'; runId: string }
  | { name: 'connections' };

export function parseHash(hash: string): Route {
  const clean = hash.replace(/^#\/?/, '').split('?')[0] ?? '';
  const parts = clean.split('/').filter(Boolean);
  if (parts[0] === 'history') {
    return parts[1] ? { name: 'history-detail', runId: decodeURIComponent(parts[1]) } : { name: 'history' };
  }
  if (parts[0] === 'connections') return { name: 'connections' };
  // `#/sohbet` is the engine; `chat` stays as an alias so old links keep working.
  if (parts[0] === 'sohbet' || parts[0] === 'chat') return { name: 'chat' };
  return { name: 'today' };
}

export function useHashRoute(): [Route, (hash: string) => void] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));

  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const navigate = (hash: string) => {
    if (window.location.hash === hash) {
      setRoute(parseHash(hash));
      return;
    }
    window.location.hash = hash;
  };

  return [route, navigate];
}
