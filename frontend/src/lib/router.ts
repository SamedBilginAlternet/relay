import { useCallback, useEffect, useState } from 'react';

export type Route =
  | { name: 'today' }
  /** `runId` is absent on a bare `#/sohbet` — the screen with nothing running yet. */
  | { name: 'chat'; runId?: string }
  | { name: 'ask' }
  | { name: 'history' }
  | { name: 'history-detail'; runId: string }
  | { name: 'connections' }
  | { name: 'policies' }
  | { name: 'panel' }
  | { name: 'login' }
  | { name: 'register' }
  | { name: 'onboarding' };

export function parseHash(hash: string): Route {
  const clean = hash.replace(/^#\/?/, '').split('?')[0] ?? '';
  const parts = clean.split('/').filter(Boolean);
  if (parts[0] === 'history') {
    return parts[1] ? { name: 'history-detail', runId: decodeURIComponent(parts[1]) } : { name: 'history' };
  }
  if (parts[0] === 'connections') return { name: 'connections' };
  // `#/politikalar` is the per-tool rule table: auto / ask / forbidden.
  if (parts[0] === 'politikalar') return { name: 'policies' };
  // `#/panel` counts what already happened; it never starts anything.
  if (parts[0] === 'panel') return { name: 'panel' };
  // `#/sor` reads the mailbox and answers; it never runs a tool, so it is not `#/sohbet`.
  if (parts[0] === 'sor') return { name: 'ask' };
  if (parts[0] === 'giris') return { name: 'login' };
  if (parts[0] === 'kayit') return { name: 'register' };
  if (parts[0] === 'onboarding') return { name: 'onboarding' };
  // `#/sohbet` is the engine; `chat` stays as an alias so old links keep working.
  // `#/sohbet/<runId>` names the flow on screen, so a refresh or a trip to
  // another tab comes back to it instead of to an empty greeting. The bare form
  // still parses: every link written before this, and the screen's own starting
  // state, are that form.
  if (parts[0] === 'sohbet' || parts[0] === 'chat') {
    return parts[1] ? { name: 'chat', runId: decodeURIComponent(parts[1]) } : { name: 'chat' };
  }
  return { name: 'today' };
}

/**
 * `replace` rewrites the address without adding a history entry.
 *
 * It is for the case where the URL is catching up with something the user
 * already did — the chat screen writing the id of the run it is showing. Back
 * should return to wherever they came from, not step through addresses for one
 * screen they never left.
 */
export type Navigate = (hash: string, opts?: { replace?: boolean }) => void;

export function useHashRoute(): [Route, Navigate] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));

  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const navigate = useCallback<Navigate>((hash, opts) => {
    if (window.location.hash === hash) {
      setRoute(parseHash(hash));
      return;
    }
    if (opts?.replace) {
      // replaceState fires no hashchange, so this state has to be set by hand.
      window.history.replaceState(null, '', hash);
      setRoute(parseHash(hash));
      return;
    }
    window.location.hash = hash;
  }, []);

  return [route, navigate];
}
