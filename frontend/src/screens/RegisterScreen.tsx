import { useState } from 'react';
import { AuthField, AuthShell, GoogleButton } from '../components/AuthShell';
import { AuthError } from '../data/AuthSource';
import type { Session } from '../lib/session';

const MIN_PASSWORD = 8;

export function RegisterScreen({
  session,
  onNavigate,
}: {
  session: Session;
  onNavigate: (hash: string) => void;
}) {
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ field: string | null; message: string } | null>(null);

  const googleUrl = session.googleStartUrl();

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;

    // Catch the two obvious mistakes before a round trip — the server checks again.
    if (!email.trim()) {
      setError({ field: 'email', message: 'E-posta gerekli.' });
      return;
    }
    if (password.length < MIN_PASSWORD) {
      setError({ field: 'password', message: `Parola en az ${MIN_PASSWORD} karakter olmalı.` });
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await session.signUp(email, password, displayName);
      onNavigate('#/onboarding');
    } catch (err) {
      setError(
        err instanceof AuthError
          ? { field: err.field, message: err.message }
          : { field: null, message: 'Hesap oluşturulamadı. Lütfen tekrar dene.' },
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell
      title="Hesap oluştur"
      lead="Bir dakika sürer. Ardından bağlantıları kurup ilk akışı çalıştırırsın."
      footer={
        <>
          Zaten hesabın var mı?{' '}
          <button type="button" className="auth__link" onClick={() => onNavigate('#/giris')}>
            Giriş yap
          </button>
        </>
      }
    >
      {error && !error.field ? (
        <p className="auth__alert" role="alert">
          {error.message}
        </p>
      ) : null}

      <form className="auth__form" onSubmit={submit} noValidate>
        <AuthField
          id="register-name"
          label="Ad (opsiyonel)"
          type="text"
          value={displayName}
          onChange={setDisplayName}
          placeholder="Ada Lovelace"
          autoComplete="name"
          disabled={busy}
        />
        <AuthField
          id="register-email"
          label="E-posta"
          type="email"
          value={email}
          onChange={setEmail}
          placeholder="ad.soyad@sirket.com"
          autoComplete="email"
          disabled={busy}
          error={error?.field === 'email' ? error.message : null}
        />
        <AuthField
          id="register-password"
          label="Parola"
          type="password"
          value={password}
          onChange={setPassword}
          placeholder="••••••••"
          autoComplete="new-password"
          disabled={busy}
          hint={`En az ${MIN_PASSWORD} karakter.`}
          error={error?.field === 'password' ? error.message : null}
        />
        <button type="submit" className="auth__btn auth__btn--primary" disabled={busy}>
          {busy ? 'Hesap oluşturuluyor…' : 'Hesap oluştur'}
        </button>
      </form>

      {googleUrl && session.state.googleLogin ? (
        <>
          <div className="auth__or">
            <span>veya</span>
          </div>
          <GoogleButton href={googleUrl} label="Google ile devam et" />
        </>
      ) : null}
    </AuthShell>
  );
}
