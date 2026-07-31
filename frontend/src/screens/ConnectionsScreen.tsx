import {
  CircleCheck,
  CircleX,
  Eye,
  EyeOff,
  Hash,
  KeyRound,
  Plug,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { getRunSource } from '../data';
import { formatRelative } from '../lib/format';
import type { Connection, ConnectionTestResult, Provider } from '../types/api';

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
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const rows = await getRunSource().getConnections();
      const map: Record<string, Connection> = {};
      for (const c of rows) map[c.provider] = c;
      setConnections(map);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Bağlantılar yüklenemedi.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="page">
      <div className="page__inner">
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

        {loadError && (
          <div className="notice notice--danger">
            <TriangleAlert size={16} aria-hidden />
            <span>{loadError}</span>
          </div>
        )}

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
  const [reveal, setReveal] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const saved = await getRunSource().saveConnection(provider, values);
      onSaved(saved);
      setValues({});
      setResult(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Kaydedilemedi.');
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
      setError(err instanceof Error ? err.message : 'Test edilemedi.');
    } finally {
      setTesting(false);
    }
  };

  return (
    <section className="card" aria-label={`${title} bağlantısı`}>
      <div className="card__head">
        <span className="card__icon" aria-hidden>
          {provider === 'jira' ? <Plug size={18} /> : <Hash size={18} />}
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
          return (
            <label className="field" key={f.key}>
              <span className="t-label">{f.label}</span>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  type={isSecret && !shown ? 'password' : 'text'}
                  value={values[f.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                  placeholder={stored ? stored : f.placeholder}
                  autoComplete="off"
                  spellCheck={false}
                  style={{ flex: '1 1 auto' }}
                />
                {isSecret && (
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
              <span className="field__hint">
                {stored ? `Kayıtlı: ${stored}` : f.hint ?? 'Henüz kayıt yok.'}
              </span>
            </label>
          );
        })}
      </div>

      {error && (
        <div className="notice notice--danger">
          <TriangleAlert size={15} aria-hidden />
          <span>{error}</span>
        </div>
      )}

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
