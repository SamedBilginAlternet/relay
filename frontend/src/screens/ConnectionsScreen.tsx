import {
  ArrowLeft,
  CircleCheck,
  CircleX,
  ExternalLink,
  Eye,
  EyeOff,
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
import { BrandMark } from '../components/BrandMark';

type FieldDef = {
  key: string;
  label: string;
  placeholder: string;
  secret?: boolean;
  hint?: string;
};

/** Which of `BrandMark`'s marks a provider is drawn with. Google is two products. */
type MarkName = 'jira' | 'github' | 'gmail' | 'calendar' | 'slack';

type ProviderDef = {
  provider: Provider;
  title: string;
  /** Two lines on the tile: what Relay does with it, not how to set it up. */
  blurb: string;
  marks: MarkName[];
  /** Where the credential actually lives — the provider's own console. */
  console: { href: string; label: string };
  fields: FieldDef[];
  /** Google has no token to paste; setting it up is a consent screen, not a form. */
  oauth?: boolean;
};

export const PROVIDERS: ProviderDef[] = [
  {
    provider: 'google',
    title: 'Google',
    blurb: 'Gmail ve Takvim, yalnızca okuma izniyle. Günün özeti ve mail sorularının kaynağı.',
    marks: ['gmail', 'calendar'],
    console: { href: 'https://myaccount.google.com/permissions', label: 'Google hesap izinleri' },
    fields: [],
    oauth: true,
  },
  {
    provider: 'jira',
    title: 'Jira',
    blurb: 'Kayıt arama ve okuma, durum güncelleme, yorum ekleme. API token ile bağlanır.',
    marks: ['jira'],
    console: {
      href: 'https://id.atlassian.com/manage-profile/security/api-tokens',
      label: 'Atlassian API token’ları',
    },
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
    blurb: 'Review bekleyen PR’lar, sana atanmış kayıtlar, yorum ekleme. Fine-grained token ile.',
    marks: ['github'],
    console: {
      href: 'https://github.com/settings/personal-access-tokens',
      label: 'GitHub fine-grained token’ları',
    },
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
    blurb: 'Kanala mesaj atma ve thread’e cevap verme. Bot token ile bağlanır.',
    marks: ['slack'],
    console: { href: 'https://api.slack.com/apps', label: 'Slack uygulama ayarları' },
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

/**
 * The connected services, and one of them being set up.
 *
 * <p>WHY THIS IS TWO STATES RATHER THAN ONE PAGE. Every provider used to be a full-width
 * card with its form permanently open — four of them, Jira's four inputs among them, about
 * 2100px on a 900px screen. The cost was not the scrolling. It was that "which of these am
 * I actually connected to" took a scroll to answer, because the four status pills sat five
 * hundred pixels apart inside four forms nobody was filling in.
 *
 * <p>So the default state answers only that question — four tiles, four marks, four states,
 * one screen — and a form is what you get after choosing a provider. One thing at a time is
 * also what keeps the longest form in the product from producing a scrollbar.
 *
 * <p>WHAT WAS NOT TAKEN from the integration-market pattern this is modelled on: the
 * on/off switch in the tile footer. `Connection` has no such field — a connection is stored
 * or it is not — so a switch would draw a third state the server cannot hold.
 */
export function ConnectionsScreen() {
  const [connections, setConnections] = useState<Record<string, Connection>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [open, setOpen] = useState<Provider | null>(null);

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

  const chosen = open ? (PROVIDERS.find((p) => p.provider === open) ?? null) : null;

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">{chosen ? chosen.title : 'Bağlantılar'}</h1>
            {/*
              No caption over an open form. The one that stood here vouched for the product
              — the token is encrypted, never logged, masked on screen — and then told the
              reader what the button below the form does. Neither is a fact the reader can
              check from this screen: the encryption claim belongs in docs/ARCHITECTURE.md
              where it can be traced to code, and a button explains itself by being pressed.
              The heading already names the provider, which is the only thing the form needs
              said about it.
            */}
            {!chosen && (
              <p className="t-caption">
                Relay yalnızca burada bağladığın servislere ulaşır. Kurulumu açmak için bir
                servise bas.
              </p>
            )}
          </div>
          {chosen ? (
            <button type="button" className="btn btn--outline btn--sm" onClick={() => setOpen(null)}>
              <ArrowLeft size={14} aria-hidden />
              Bağlantılar
            </button>
          ) : (
            <button type="button" className="btn btn--outline btn--sm" onClick={() => void load()}>
              <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
              Yenile
            </button>
          )}
        </div>

        {loadError != null && <LoadError error={loadError} onRetry={() => void load()} />}

        {loading && (
          <div className="int-grid">
            <div className="skeleton" style={{ height: 164 }} />
            <div className="skeleton" style={{ height: 164, opacity: 0.7 }} />
            <div className="skeleton" style={{ height: 164, opacity: 0.5 }} />
            <div className="skeleton" style={{ height: 164, opacity: 0.35 }} />
          </div>
        )}

        {!loading && !chosen && (
          <>
            <div className="int-grid">
              {PROVIDERS.map((p) => (
                <ProviderTile
                  key={p.provider}
                  def={p}
                  connection={connections[p.provider]}
                  onOpen={() => setOpen(p.provider)}
                />
              ))}
            </div>

            <div className="notice">
              <ShieldCheck size={16} aria-hidden />
              <span>
                Politika varsayılanı: okuma araçları otomatik, yazma araçları onay ister, silme
                araçları yasaktır. Onay kapısı iş akışı panelinde çalışır.
              </span>
            </div>
          </>
        )}

        {!loading && chosen?.oauth && <GoogleSetup connection={connections.google} />}

        {!loading && chosen && !chosen.oauth && (
          <ProviderSetup
            key={chosen.provider}
            def={chosen}
            connection={connections[chosen.provider]}
            onSaved={(c) => setConnections((cur) => ({ ...cur, [c.provider]: c }))}
          />
        )}
      </div>
    </div>
  );
}

/**
 * One service, at a glance: whose it is, what Relay does with it, whether it is on.
 *
 * <p>The mark carries the recognition and the word carries the claim — a coloured dot alone
 * would leave "bağlı" to colour vision. The link in the corner goes to the provider's own
 * console, where the credential this tile is about actually lives; it is deliberately not
 * the tile's main action, so setting a connection up can never leave the product by
 * accident.
 */
function ProviderTile({
  def,
  connection,
  onOpen,
}: {
  def: ProviderDef;
  connection: Connection | undefined;
  onOpen: () => void;
}) {
  const connected = Boolean(connection?.configured);
  return (
    <article className="int" aria-label={`${def.title} bağlantısı`}>
      <div className="int__top">
        <span className="int__marks">
          {def.marks.map((m) => (
            <BrandMark key={m} provider={m} size={22} />
          ))}
        </span>
        <a
          className="int__out"
          href={def.console.href}
          target="_blank"
          rel="noreferrer noopener"
          title={def.console.label}
          aria-label={`${def.console.label} — yeni sekmede açılır`}
        >
          <ExternalLink size={14} aria-hidden />
        </a>
      </div>

      <h2 className="int__name">{def.title}</h2>
      <p className="int__blurb">{def.blurb}</p>

      <div className="int__foot">
        <button type="button" className="btn btn--outline btn--sm" onClick={onOpen}>
          <Plug size={14} aria-hidden />
          {connected ? 'Yönet' : 'Bağlan'}
        </button>
        <span className={`int__state${connected ? ' int__state--on' : ''}`}>
          <span className="int__dot" aria-hidden />
          {connected ? 'Bağlı' : 'Bağlı değil'}
        </span>
      </div>
    </article>
  );
}

/**
 * Google is the odd one out: there is no token to paste, so this offers the consent flow
 * instead of a form — and, when the server has no client id yet, says exactly which
 * environment variables are missing rather than showing a button that cannot work.
 */
function GoogleSetup({ connection }: { connection: Connection | undefined }) {
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
    <section className="card" aria-label="Google kurulumu">
      <p className="t-caption">
        Token yapıştırılmaz: Google’ın izin ekranından geçersin, yetki sunucuda şifreli saklanır.
        İzinler yalnızca okuma (gmail.readonly, calendar.readonly).
      </p>

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
        {status?.redirectUri && <span className="t-caption">Dönüş adresi: {status.redirectUri}</span>}
      </div>
    </section>
  );
}

function ProviderSetup({
  def,
  connection,
  onSaved,
}: {
  def: ProviderDef;
  connection: Connection | undefined;
  onSaved: (c: Connection) => void;
}) {
  const { provider, fields } = def;
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
    <section className="card" aria-label={`${def.title} kurulumu`}>
      <p className="t-caption">
        {def.blurb}{' '}
        <a href={def.console.href} target="_blank" rel="noreferrer noopener">
          {def.console.label}
        </a>
      </p>

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
                  {holding
                    ? 'Değiştirmek için tıkla — yenisini yapıştırana kadar bu token geçerli.'
                    : f.hint}
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
        <button
          type="button"
          className="btn btn--outline btn--sm"
          onClick={() => void test()}
          disabled={testing}
        >
          {testing ? (
            <RefreshCw size={14} aria-hidden className="spin" />
          ) : (
            <ShieldCheck size={14} aria-hidden />
          )}
          {testing ? 'Test ediliyor…' : 'Test et'}
        </button>
        {connection?.updatedAt && (
          <span className="t-caption">Son güncelleme: {formatRelative(connection.updatedAt)}</span>
        )}
        {result && (
          <span
            className={`test-result ${result.ok ? 'test-result--ok' : 'test-result--fail'}`}
            role="status"
          >
            {result.ok ? <CircleCheck size={15} aria-hidden /> : <CircleX size={15} aria-hidden />}
            {result.message}
          </span>
        )}
      </div>
    </section>
  );
}
