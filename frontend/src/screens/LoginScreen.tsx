import { useState } from 'react';
import { AuthField, AuthShell, GoogleButton } from '../components/AuthShell';
import { AuthError } from '../data/AuthSource';
import type { Session } from '../lib/session';

/** Reasons the Google round-trip can come back empty-handed. */
const GOOGLE_ERRORS: Record<string, string> = {
  google_denied: 'Google girişini onaylamadın. E-posta ve parolayla da girebilirsin.',
  state_mismatch: 'Google girişi doğrulanamadı. Lütfen tekrar dene.',
  email_not_verified: 'Google hesabının e-postası doğrulanmamış. Doğruladıktan sonra tekrar dene.',
};

function googleError(): string | null {
  const match = /[?&]hata=([^&]+)/.exec(window.location.hash);
  const code = match?.[1];
  return code ? (GOOGLE_ERRORS[code] ?? 'Google ile giriş tamamlanamadı.') : null;
}

export function LoginScreen({
  session,
  onNavigate,
}: {
  session: Session;
  onNavigate: (hash: string) => void;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ field: string | null; message: string } | null>(
    () => {
      const message = googleError();
      return message ? { field: null, message } : null;
    },
  );

  const googleUrl = session.googleStartUrl();

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await session.signIn(email, password);
      onNavigate('#/');
    } catch (err) {
      setError(
        err instanceof AuthError
          ? { field: err.field, message: err.message }
          : { field: null, message: 'Giriş yapılamadı. Lütfen tekrar dene.' },
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell
      title="Giriş yap"
      lead="Relay hesabınla devam et. Aynı çalışma alanını ekip olarak paylaşırsınız."
      footer={
        <>
          Hesabın yok mu?{' '}
          <button type="button" className="auth__link" onClick={() => onNavigate('#/kayit')}>
            Hesap oluştur
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
          id="login-email"
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
          id="login-password"
          label="Parola"
          type="password"
          value={password}
          onChange={setPassword}
          placeholder="••••••••"
          autoComplete="current-password"
          disabled={busy}
          error={error?.field === 'password' ? error.message : null}
        />
        <button type="submit" className="auth__btn auth__btn--primary" disabled={busy}>
          {busy ? 'Giriş yapılıyor…' : 'Giriş yap'}
        </button>
      </form>

      {googleUrl && session.state.googleLogin ? (
        <>
          <div className="auth__or">
            <span>veya</span>
          </div>
          <GoogleButton href={googleUrl} label="Google ile devam et" />
          <p className="auth__note">
            Yalnızca adın ve e-postan alınır. Gmail ve Takvim erişimi ayrı bir onaydır —
            Bağlantılar ekranından, istediğinde.
          </p>
        </>
      ) : null}
    </AuthShell>
  );
}
