import {
  Ban,
  Eye,
  GitPullRequest,
  Hand,
  Hash,
  Mail,
  Pencil,
  Plug,
  RefreshCw,
  RotateCcw,
  ShieldQuestion,
  Trash2,
  TriangleAlert,
  Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { LoadError } from '../components/LoadError';
import { defaultModeFor, getPolicySource } from '../data/PolicySource';
import type { PolicyMode, RiskLevel, ToolPolicy } from '../data/PolicySource';

const MODES: { key: PolicyMode; label: string; Icon: LucideIcon; hint: string }[] = [
  { key: 'auto', label: 'Otomatik', Icon: Zap, hint: 'onay sorulmadan çalışır' },
  { key: 'ask', label: 'Onay ister', Icon: Hand, hint: 'akış onay kapısında durur' },
  { key: 'forbidden', label: 'Yasak', Icon: Ban, hint: 'hiç çağrılmaz' },
];

const RISKS: Record<RiskLevel, { label: string; Icon: LucideIcon }> = {
  read: { label: 'okuma', Icon: Eye },
  write: { label: 'yazma', Icon: Pencil },
  destructive: { label: 'silme', Icon: Trash2 },
};

/** Same order as Bağlantılar, so the two screens read as one product. */
const PROVIDER_ORDER = ['jira', 'github', 'slack', 'google'];
const PROVIDERS: Record<string, { title: string; Icon: LucideIcon }> = {
  jira: { title: 'Jira', Icon: Plug },
  github: { title: 'GitHub', Icon: GitPullRequest },
  slack: { title: 'Slack', Icon: Hash },
  google: { title: 'Google — Gmail + Takvim', Icon: Mail },
};

function modeLabel(mode: PolicyMode): string {
  return MODES.find((m) => m.key === mode)?.label ?? mode;
}

function providerRank(provider: string): number {
  const index = PROVIDER_ORDER.indexOf(provider);
  return index < 0 ? PROVIDER_ORDER.length : index;
}

/**
 * The rule the whole product is sold on — *read runs, write asks, delete is
 * forbidden* — with the receipts. Every registered tool, its risk, the mode it
 * is actually running under, and whether that mode is still the default.
 *
 * The screen only ever writes one thing: `PUT /api/policies` with a single
 * `{toolName, mode}` row. There is no "delete override" endpoint, so going back
 * to the default means writing the default mode — see `DEFAULT_MODE`.
 */
export function PolicyScreen() {
  const [rows, setRows] = useState<ToolPolicy[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [busyTool, setBusyTool] = useState<string | null>(null);
  const [note, setNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await getPolicySource().list());
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const change = async (row: ToolPolicy, mode: PolicyMode) => {
    if (row.mode === mode || busyTool) return;
    setBusyTool(row.toolName);
    setSaveError(null);
    try {
      // The endpoint answers with the whole table, so the screen never has to
      // guess what the server ended up storing.
      setRows(await getPolicySource().setMode(row.toolName, mode));
      setNote(`${row.toolName} artık: ${modeLabel(mode).toLocaleLowerCase('tr')}.`);
    } catch (err) {
      setSaveError(err);
    } finally {
      setBusyTool(null);
    }
  };

  const groups = useMemo(() => {
    const byProvider = new Map<string, ToolPolicy[]>();
    for (const row of rows ?? []) {
      const list = byProvider.get(row.provider);
      if (list) list.push(row);
      else byProvider.set(row.provider, [row]);
    }
    return [...byProvider.entries()]
      .sort((a, b) => providerRank(a[0]) - providerRank(b[0]) || a[0].localeCompare(b[0], 'tr'))
      .map(([provider, tools]) => ({ provider, tools }));
  }, [rows]);

  const counts = useMemo(() => {
    const out: Record<PolicyMode, number> = { auto: 0, ask: 0, forbidden: 0 };
    for (const row of rows ?? []) out[row.mode] += 1;
    return out;
  }, [rows]);

  const changed = useMemo(
    () => (rows ?? []).filter((r) => r.mode !== defaultModeFor(r.risk)),
    [rows],
  );

  return (
    <div className="page">
      <div className="page__inner pol">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Politikalar</h1>
            <p className="t-caption">
              Her araç için tek bir kural: <b>okuma otomatik çalışır</b>,{' '}
              <b>yazma onay ister</b>, <b>silme yasaktır</b>. Varsayılan aracın risk
              seviyesinden gelir; buradan araç bazında değiştirilir ve karar anında motor
              bu tabloya bakar.
            </p>
          </div>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            onClick={() => void load()}
            disabled={loading}
          >
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        <p className="sr-only" role="status" aria-live="polite">
          {loading ? 'Politikalar yükleniyor.' : note}
        </p>

        {error != null && <LoadError error={error} onRetry={() => void load()} />}

        {saveError != null && (
          <LoadError error={saveError} onRetry={() => void load()} retryLabel="Tabloyu yenile" />
        )}

        {loading && (
          <>
            <div className="skeleton" style={{ height: 64 }} />
            <div className="skeleton" style={{ height: 220, opacity: 0.6 }} />
            <div className="skeleton" style={{ height: 180, opacity: 0.4 }} />
          </>
        )}

        {!loading && rows && (
          <>
            {/* The sentence, counted. A jury asking "where do I see it" gets a
                number per mode before reading a single row. */}
            <div className="pol-sum">
              {MODES.map((mode) => (
                <div className={`pol-sum__cell pol-sum__cell--${mode.key}`} key={mode.key}>
                  <span className="pol-sum__n">{counts[mode.key]}</span>
                  <span className="pol-sum__label">
                    <mode.Icon size={13} aria-hidden />
                    {mode.label}
                  </span>
                  <span className="pol-sum__hint">{mode.hint}</span>
                </div>
              ))}
            </div>

            {changed.length > 0 && (
              <div className="notice notice--warn">
                <TriangleAlert size={16} aria-hidden />
                <span>
                  {changed.length} araç varsayılanından farklı çalışıyor:{' '}
                  {changed.map((r) => r.toolName).join(', ')}.
                </span>
              </div>
            )}

            {groups.map(({ provider, tools }) => {
              const meta = PROVIDERS[provider] ?? { title: provider, Icon: Plug };
              const headId = `pol-${provider}`;
              return (
                <section className="pol-group" key={provider} aria-labelledby={headId}>
                  <div className="pol-group__head">
                    <span className="pol-group__icon" aria-hidden>
                      <meta.Icon size={16} />
                    </span>
                    <h2 className="t-title" id={headId}>
                      {meta.title}
                    </h2>
                    <span className="t-caption">{tools.length} araç</span>
                  </div>
                  <ul className="pol-list">
                    {tools.map((row) => (
                      <PolicyRow
                        key={row.toolName}
                        row={row}
                        busy={busyTool === row.toolName}
                        disabled={busyTool !== null && busyTool !== row.toolName}
                        onChange={(mode) => void change(row, mode)}
                      />
                    ))}
                  </ul>
                </section>
              );
            })}

            {/* The rule that has no row of its own, because it is about the tools
                that are NOT in this table. Issue #14 asks for it explicitly. */}
            <section className="pol-group pol-group--rule" aria-labelledby="pol-unknown">
              <div className="pol-group__head">
                <span className="pol-group__icon" aria-hidden>
                  <ShieldQuestion size={16} />
                </span>
                <h2 className="t-title" id="pol-unknown">
                  Listede olmayan her araç
                </h2>
              </div>
              <div className="pol-unknown">
                <div className="pol-row__id">
                  <code className="pol-row__name t-mono">bilinmeyen araç adı</code>
                  <span className="pol-risk pol-risk--destructive">
                    <Trash2 size={12} aria-hidden />
                    risk bilinmiyor
                  </span>
                </div>
                <span className="pol-fixed">
                  <Ban size={13} aria-hidden />
                  Yasak — değiştirilemez
                </span>
                <p className="pol-row__src">
                  Bir plan bu tabloda olmayan bir araç adı üretirse motor onu çalıştırmadan
                  reddeder (<code className="t-mono">unknown tool</code>). Kayıtlı araç yoksa
                  risk de bilinmiyordur; bilinmeyen riskin varsayılanı en dar olanıdır.
                </p>
              </div>
              {counts.forbidden === 0 && (
                <p className="pol-note">
                  Şu an kayıtlı hiçbir aracın riski <b>silme</b> değil — bu yüzden tabloda
                  yasak satırı görünmüyor. Kural boşta durmuyor: silme riskli bir araç
                  eklendiği gün varsayılanı yasak olarak gelir, üstteki satır da her zaman
                  yasaktır.
                </p>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}

type RowProps = {
  row: ToolPolicy;
  busy: boolean;
  disabled: boolean;
  onChange: (mode: PolicyMode) => void;
};

function PolicyRow({ row, busy, disabled, onChange }: RowProps) {
  const fallback = defaultModeFor(row.risk);
  const deviates = row.mode !== fallback;
  const risk = RISKS[row.risk];

  return (
    <li className={`pol-row${deviates ? ' pol-row--off' : ''}`}>
      <div className="pol-row__id">
        <code className="pol-row__name t-mono">{row.toolName}</code>
        <span className={`pol-risk pol-risk--${row.risk}`}>
          <risk.Icon size={12} aria-hidden />
          {risk.label}
        </span>
      </div>

      {/* Native radios: one group per tool, so arrow keys move between the three
          modes and the browser does the roving focus for free. */}
      <div className="pol-seg" role="group" aria-label={`${row.toolName} için mod`}>
        {MODES.map((mode) => {
          const on = row.mode === mode.key;
          return (
            <label
              key={mode.key}
              className={`pol-seg__opt${on ? ` pol-seg__opt--on pol-seg__opt--${mode.key}` : ''}`}
            >
              <input
                type="radio"
                className="sr-only"
                name={`mode-${row.toolName}`}
                value={mode.key}
                checked={on}
                disabled={busy || disabled}
                onChange={() => onChange(mode.key)}
              />
              <mode.Icon size={13} aria-hidden />
              <span>{mode.label}</span>
            </label>
          );
        })}
      </div>

      <p className="pol-row__src">
        {busy ? (
          <span className="pol-row__saving">
            <RefreshCw size={12} aria-hidden className="spin" />
            Kaydediliyor…
          </span>
        ) : deviates ? (
          <>
            <span className="pol-row__flag">
              <TriangleAlert size={12} aria-hidden />
              Operatör değiştirdi
            </span>
            <span>
              varsayılan: {modeLabel(fallback).toLocaleLowerCase('tr')} ({risk.label} riski)
            </span>
            <button
              type="button"
              className="btn btn--ghost btn--sm pol-row__reset"
              onClick={() => onChange(fallback)}
              disabled={disabled}
            >
              <RotateCcw size={13} aria-hidden />
              Varsayılana dön
            </button>
          </>
        ) : (
          <span className="pol-row__ok">
            Varsayılan — {risk.label} riski {modeLabel(fallback).toLocaleLowerCase('tr')}
          </span>
        )}
      </p>
    </li>
  );
}
