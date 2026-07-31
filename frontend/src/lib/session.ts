import { useCallback, useEffect, useState } from 'react';
import { AuthError, getAuthSource } from '../data/AuthSource';
import type { SessionState } from '../types/auth';
import { ANONYMOUS } from '../types/auth';

export type SessionStatus = 'loading' | 'anonymous' | 'authenticated' | 'offline';

export type Session = {
  status: SessionStatus;
  state: SessionState;
  /** Set when the backend could not be reached at all. */
  error: string | null;
  signIn(email: string, password: string): Promise<void>;
  signUp(email: string, password: string, displayName: string): Promise<void>;
  signOut(): Promise<void>;
  finishOnboarding(): Promise<void>;
  refresh(): Promise<void>;
  googleStartUrl(): string | null;
};

/**
 * Who is signed in, asked once on boot.
 *
 * The token itself is an HttpOnly cookie the browser handles — this hook never sees
 * it, which is the point: a stolen `localStorage` cannot become a session.
 */
export function useSession(): Session {
  const [state, setState] = useState<SessionState>(ANONYMOUS);
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [error, setError] = useState<string | null>(null);

  const apply = useCallback((next: SessionState) => {
    setState(next);
    setStatus(next.authenticated ? 'authenticated' : 'anonymous');
    setError(null);
  }, []);

  const refresh = useCallback(async () => {
    try {
      apply(await getAuthSource().me());
    } catch (err) {
      // Unreachable backend is its own state: showing a login form that can never
      // succeed would blame the user for a server problem.
      setStatus('offline');
      setError(err instanceof Error ? err.message : 'Sunucuya ulaşılamadı.');
    }
  }, [apply]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      apply(await getAuthSource().login(email, password));
    },
    [apply],
  );

  const signUp = useCallback(
    async (email: string, password: string, displayName: string) => {
      apply(await getAuthSource().register(email, password, displayName));
    },
    [apply],
  );

  const signOut = useCallback(async () => {
    try {
      await getAuthSource().logout();
    } catch {
      /* the cookie may already be gone — the UI still returns to signed out */
    }
    setState(ANONYMOUS);
    setStatus('anonymous');
    window.location.hash = '#/giris';
  }, []);

  const finishOnboarding = useCallback(async () => {
    try {
      apply(await getAuthSource().completeOnboarding());
    } catch (err) {
      // Never trap someone in the tour because one call failed.
      if (err instanceof AuthError && err.status === 401) {
        setState(ANONYMOUS);
        setStatus('anonymous');
        return;
      }
      setState((prev) =>
        prev.user ? { ...prev, user: { ...prev.user, onboarded: true } } : prev,
      );
    }
  }, [apply]);

  return {
    status,
    state,
    error,
    signIn,
    signUp,
    signOut,
    finishOnboarding,
    refresh,
    googleStartUrl: () => getAuthSource().googleStartUrl(),
  };
}
