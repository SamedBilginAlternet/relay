import { useEffect } from 'react';
import type { ReactNode } from 'react';
import '../styles/auth.css';
import { AccountMenu } from '../components/AccountMenu';
import type { Route } from '../lib/router';
import { useSession } from '../lib/session';
import { LoginScreen } from './LoginScreen';
import { OnboardingScreen } from './OnboardingScreen';
import { RegisterScreen } from './RegisterScreen';

/**
 * Nothing in the app renders before we know who is asking.
 *
 * Three gates, in order: signed in? onboarded? then the app. The onboarding gate has
 * one deliberate hole — `#/connections` stays reachable, because step 2 of the tour
 * sends people there and bouncing them back would be a trap.
 */
export function AuthGate({
  route,
  onNavigate,
  children,
}: {
  route: Route;
  onNavigate: (hash: string) => void;
  children: ReactNode;
}) {
  const session = useSession();
  const user = session.state.user;
  const onAuthRoute = route.name === 'login' || route.name === 'register';

  // The URL and the state have to agree, or the back button starts lying.
  useEffect(() => {
    if (session.status === 'anonymous' && !onAuthRoute) {
      onNavigate('#/giris');
    }
    if (session.status === 'authenticated' && onAuthRoute) {
      onNavigate(user && !user.onboarded ? '#/onboarding' : '#/');
    }
  }, [session.status, onAuthRoute, onNavigate, user]);

  if (session.status === 'loading') {
    return (
      <div className="auth auth--splash">
        <p className="auth__splash-text">Oturum kontrol ediliyor…</p>
      </div>
    );
  }

  if (session.status === 'offline') {
    return (
      <div className="auth auth--splash">
        <div className="auth__card auth__card--wide">
          <h1 className="auth__title">Sunucuya ulaşılamıyor</h1>
          <p className="auth__lead">{session.error ?? 'Backend yanıt vermiyor.'}</p>
          <button type="button" className="auth__btn auth__btn--primary" onClick={() => void session.refresh()}>
            Tekrar dene
          </button>
        </div>
      </div>
    );
  }

  if (session.status === 'anonymous' || !user) {
    return route.name === 'register' ? (
      <RegisterScreen session={session} onNavigate={onNavigate} />
    ) : (
      <LoginScreen session={session} onNavigate={onNavigate} />
    );
  }

  const mustOnboard = !user.onboarded && route.name !== 'connections';
  if (mustOnboard || route.name === 'onboarding') {
    return <OnboardingScreen session={session} onNavigate={onNavigate} />;
  }

  return (
    <>
      {children}
      <AccountMenu session={session} onNavigate={onNavigate} />
    </>
  );
}
