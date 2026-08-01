import {
  CircleCheck,
  CircleX,
  Eye,
  EyeOff,
  GitPullRequest,
  Hash,
  KeyRound,
  Mail,
  Plug,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { LoadError } from '../components/LoadError';
import { API_BASE_URL, getRunSource } from '../data';
import { formatRelative } from '../lib/format';
import type { Connection, ConnectionTestResult, GoogleStatus, Provider } from '../types/api';
import '../styles/screens.css';

type FieldDef = {
  key: string;
  label: string;
  placeholder: string;
  secret?: boolean;
  hint?: string;
};

const PROVIDERS: {
  provider: Provider;
  title: string;
  blurb: string;
  fields: FieldDef[];
}[] = [
  {
    provider: 'jira',
    title: 'Jira',
    blurb: 'API token ile bağlanır (OAuth yok). Issue arama, okuma, durum güncelleme, yorum ekleme.',
    fields: [
      { key: 'baseUrl', label: 'Site adresi', placeholder: 'https://sirket.atlassian.net' },
      { key: 'email', label: 'E-posta', placeholder: 'ad.soyad@sirket.com' },
      {
        key: 'apiToken',
        label: 'API token',
        placeholder: 'ATATT3xFfGF0…',
        secret: true,
        hint: 'id.atlassian.com › Security › API tokens',
      },
      { key: 'projectKey', label: 'Varsayılan proje (opsiyonel)', placeholder: 'RUN' },
    ],
  },
  {
    provider: 'github',
    title: 'GitHub',
    blurb: 'Fine-grained personal access token ile bağlanır (OAuth yok). Review bekleyen PR’lar, sana atanmış issue’lar, yorum ekleme.',
    fields: [
      {
        key: 'token',
        label: 'Personal access token',
        placeholder: 'github_pat_…',
        secret: true,
        hint: 'github.com › Settings › Developer settings › Fine-grained tokens · izinler: Pull requests + Issues (Read and write), Metadata (Read)',
      },
      {
        key: 'login',
        label: 'Kullanıcı adı (opsiyonel)',
        placeholder: 'kullanici-adi',
        hint: 'Boş bırakılırsa aramalar @me ile yapılır.',
      },
    ],
  },
  {
    provider: 'slack',
    title: 'Slack',
    blurb: 'Bot token ile bağlanır. Kanala mesaj atma ve thread’e cevap verme.',
    fields: [
      {
        key: 'botToken',
        label: 'Bot token',
        placeholder: 'xoxb-…',
        secret: true,
        hint: 'api.slack.com › OAuth & Permissions › Bot User OAuth Token',
      },
      { key: 'defaultChannel', label: 'Varsayılan kanal (opsiyonel)', placeholder: '#dev-sprint' },
    ],
  },
];

export function ConnectionsScreen() {
  const [connections, setConnections] = useState<Record<string, Connection>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const rows = await getRunSource().getConnections();
      const map: Record<string, Connection> = {};
      for (const c of rows) map[c.provider] = c;
      setConnections(map);
    } catch (err) {
      setLoadError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Bağlantılar</h1>
            <p className="t-caption">
              Token’lar sunucuda AES-GCM ile şifrelenir, log’a hiç yazılmaz ve burada maskeli
              görünür. Kaydettikten sonra “Test et” ile doğrula.
            </p>
          </div>
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        {loadError != null && <LoadError error={loadError} onRetry={() => void load()} />}

        {loading && (
          <>
            <div className="skeleton" style={{ height: 220 }} />
            <div className="skeleton" style={{ height: 180, opacity: 0.6 }} />
          </>
        )}

        {!loading &&
          PROVIDERS.map((p) => (
            <ProviderCard
              key={p.provider}
              provider={p.provider}
              title={p.title}
              blurb={p.blurb}
              fields={p.fields}
              connection={connections[p.provider]}
              onSaved={(c) => setConnections((cur) => ({ ...cur, [c.provider]: c }))}
            />
          ))}

        {!loading && <GoogleCard connection={connections.google} />}

        <div className="notice">
          <ShieldCheck size={16} aria-hidden />
          <span>
            Politika varsayılanı: okuma araçları otomatik, yazma araçları onay ister, silme araçları
            yasaktır. Onay kapısı iş akışı panelinde çalışır.
          </span>
        </div>
      </div>
    </div>
  );
}

/**
 * Google is the odd one out: there is no token to paste, so this card offers the consent
 * flow instead of a form — and, when the server has no client id yet, says exactly which
 * environment variables are missing rather than showing an input that cannot work.
 */
function GoogleCard({ connection }: { connection: Connection | undefined }) {
  const [status, setStatus] = useState<GoogleStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const next = await getRunSource().getGoogleStatus();
        if (alive) setStatus(next);
      } catch (err) {
        if (alive) setError(err);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const connected = Boolean(status?.connected || connection?.configured);
  const apiBase = API_BASE_URL.replace(/\/+$/, '');

  return (
    <section className="card" aria-label="Google bağlantısı">
      <div className="card__head">
        <span className="card__icon" aria-hidden>
          <Mail size={18} />
        </span>
        <div style={{ flex: '1 1 auto', minWidth: 0 }}>
          <h2 className="t-title">Google — Gmail + Takvim</h2>
          <p className="t-caption">
            Token yapıştırılmaz: Google’ın izin ekranından geçersin, yetki sunucuda şifreli
            saklanır. İzinler yalnızca okuma (gmail.readonly, calendar.readonly).
          </p>
        </div>
        <span className={`status-pill ${connected ? 'st-done' : 'st-pending'}`}>
          {connected ? <CircleCheck size={13} aria-hidden /> : <KeyRound size={13} aria-hidden />}
          {connected ? 'Bağlı' : 'Boş'}
        </span>
      </div>

      {loading && <div className="skeleton" style={{ height: 56 }} />}

      {!loading && error != null && <LoadError error={error} />}

      {!loading && !error && status && !status.configured && (
        <div className="notice notice--warn">
          <TriangleAlert size={15} aria-hidden />
          <span>
            Sunucuda Google istemcisi tanımlı değil. Ortam değişkenleri gerekiyor:{' '}
            <code>GOOGLE_CLIENT_ID</code>, <code>GOOGLE_CLIENT_SECRET</code>,{' '}
            <code>GOOGLE_REDIRECT_URI</code>. Ayrıntı: <code>docs/INTEGRATIONS.md</code> §4.
          </span>
        </div>
      )}

      <div className="card__actions">
        <a
          className={`btn btn--sm${status?.configured ? '' : ' btn--disabled'}`}
          href={`${apiBase}/oauth/google/start`}
          aria-disabled={status?.configured ? undefined : true}
          onClick={(e) => {
            if (!status?.configured) e.preventDefault();
          }}
        >
          <Plug size={14} aria-hidden />
          {connected ? 'Yeniden bağlan' : 'Google ile bağlan'}
        </a>
        {status?.redirectUri && (
          <span className="t-caption">Dönüş adresi: {status.redirectUri}</span>
        )}
      </div>
    </section>
  );
}

type CardProps = {
  provider: Provider;
  title: string;
  blurb: string;
  fields: FieldDef[];
  connection: Connection | undefined;
  onSaved: (c: Connection) => void;
};

function ProviderCard({ provider, title, blurb, fields, connection, onSaved }: CardProps) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [replacing, setReplacing] = useState<Record<string, boolean>>({});
  const [reveal, setReveal] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<unknown>(null);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      // Only what was actually typed goes up. An emptied field is not an
      // instruction to delete the stored value — there is no undo behind it,
      // and a blank input is far more often a slip than a decision.
      const payload: Record<string, string> = {};
      for (const [key, value] of Object.entries(values)) {
        if (value.trim()) payload[key] = value.trim();
      }
      const saved = await getRunSource().saveConnection(provider, payload);
      onSaved(saved);
      setValues({});
      setReplacing({});
      setReveal({});
      setResult(null);
    } catch (err) {
      setError(err);
    } finally {
      setSaving(false);
    }
  };

  const test = async () => {
    setTesting(true);
    setError(null);
    try {
      setResult(await getRunSource().testConnection(provider));
    } catch (err) {
      setError(err);
    } finally {
      setTesting(false);
    }
  };

  return (
    <section className="card" aria-label={`${title} bağlantısı`}>
      <div className="card__head">
        <span className="card__icon" aria-hidden>
          {provider === 'jira' ? <Plug size={18} />
            : provider === 'github' ? <GitPullRequest size={18} />
            : <Hash size={18} />}
        </span>
        <div style={{ flex: '1 1 auto', minWidth: 0 }}>
          <h2 className="t-title">{title}</h2>
          <p className="t-caption">{blurb}</p>
        </div>
        <span className={`status-pill ${connection?.configured ? 'st-done' : 'st-pending'}`}>
          {connection?.configured ? <CircleCheck size={13} aria-hidden /> : <KeyRound size={13} aria-hidden />}
          {connection?.configured ? 'Kayıtlı' : 'Boş'}
        </span>
      </div>

      <div className="field-grid">
        {fields.map((f) => {
          const stored = connection?.config?.[f.key] ?? '';
          const isSecret = Boolean(f.secret);
          const shown = reveal[f.key] ?? false;
          // A saved secret comes back masked, so it cannot be edited in place: the
          // field shows it as the value it is, and touching it starts a replacement.
          const holding = isSecret && Boolean(stored) && !replacing[f.key];
          return (
            <label className="field" key={f.key}>
              <span className="t-label">{f.label}</span>
              <div style={{ display: 'flex', gap: 8 }}>
                {holding ? (
                  <input
                    type="text"
                    className="conn-value"
                    value={stored}
                    readOnly
                    autoComplete="off"
                    spellCheck={false}
                    style={{ flex: '1 1 auto' }}
                    onFocus={() => setReplacing((r) => ({ ...r, [f.key]: true }))}
                  />
                ) : (
                  <input
                    type={isSecret && !shown ? 'password' : 'text'}
                    value={values[f.key] ?? (isSecret ? '' : stored)}
                    onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                    placeholder={isSecret && stored ? 'Yeni token yapıştır' : f.placeholder}
                    autoComplete="off"
                    spellCheck={false}
                    autoFocus={replacing[f.key]}
                    style={{ flex: '1 1 auto' }}
                    // Left empty, the replacement is a change of mind, not a deletion:
                    // the stored token comes back and nothing was sent anywhere.
                    onBlur={() => {
                      if (isSecret && stored && !(values[f.key] ?? '').trim()) {
                        setReplacing((r) => ({ ...r, [f.key]: false }));
                      }
                    }}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape' && isSecret && stored) {
                        setValues((v) => ({ ...v, [f.key]: '' }));
                        setReplacing((r) => ({ ...r, [f.key]: false }));
                      }
                    }}
                  />
                )}
                {isSecret && !holding && (
                  <button
                    type="button"
                    className="btn btn--ghost btn--icon"
                    onClick={() => setReveal((r) => ({ ...r, [f.key]: !shown }))}
                    aria-label={shown ? 'Gizle' : 'Girdiğin değeri göster'}
                  >
                    {shown ? <EyeOff size={15} aria-hidden /> : <Eye size={15} aria-hidden />}
                  </button>
                )}
              </div>
              {(holding || f.hint) && (
                <span className="field__hint">
                  {holding ? 'Değiştirmek için tıkla — yenisini yapıştırana kadar bu token geçerli.' : f.hint}
                </span>
              )}
            </label>
          );
        })}
      </div>

      {error != null && <LoadError error={error} />}

      <div className="card__actions">
        <button
          type="button"
          className="btn btn--sm"
          onClick={() => void save()}
          disabled={saving || Object.values(values).every((v) => !v.trim())}
        >
          {saving ? 'Kaydediliyor…' : 'Kaydet'}
        </button>
        <button type="button" className="btn btn--outline btn--sm" onClick={() => void test()} disabled={testing}>
          {testing ? <RefreshCw size={14} aria-hidden className="spin" /> : <ShieldCheck size={14} aria-hidden />}
          {testing ? 'Test ediliyor…' : 'Test et'}
        </button>
        {connection?.updatedAt && (
          <span className="t-caption">Son güncelleme: {formatRelative(connection.updatedAt)}</span>
        )}
        {result && (
          <span className={`test-result ${result.ok ? 'test-result--ok' : 'test-result--fail'}`} role="status">
            {result.ok ? <CircleCheck size={15} aria-hidden /> : <CircleX size={15} aria-hidden />}
            {result.message}
          </span>
        )}
      </div>
    </section>
  );
}
