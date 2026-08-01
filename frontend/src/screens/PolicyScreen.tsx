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
import { BrandMark, providerOf } from '../components/BrandMark';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { TabStrip } from '../components/TabStrip';
import { defaultModeFor, getPolicySource } from '../data/PolicySource';
import type { PolicyMode, RiskLevel, ToolPolicy } from '../data/PolicySource';
import '../styles/screens.css';

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

/**
 * Which rule the table is filtered to, or all of them.
 *
 * <p>The screen used to draw a three-cell strip counting the modes — 12 / 6 / 0 — above a
 * table showing all eighteen rows at once. Nobody asks how many tools run unattended; they
 * ask which ones. The counts move onto the tabs, where the same three numbers select
 * something instead of only being read (#139).
 */
export type PolicyTab = 'tumu' | 'otomatik' | 'onay' | 'yasak';

/** The tab's own name in the address, and the mode it filters to. */
const TABS: { id: PolicyTab; label: string; mode: PolicyMode | null }[] = [
  { id: 'tumu', label: 'Tümü', mode: null },
  { id: 'otomatik', label: 'Otomatik', mode: 'auto' },
  { id: 'onay', label: 'Onay ister', mode: 'ask' },
  { id: 'yasak', label: 'Yasak', mode: 'forbidden' },
];

/** What each rule does, on the control that selects it — see `TabDef.hint`. */
function tabHint(mode: PolicyMode | null): string | undefined {
  return mode ? MODES.find((m) => m.key === mode)?.hint : undefined;
}

/**
 * A query, not a path segment, and the same shape Akışlar uses.
 *
 * <p>`#/politikalar/<x>` has no meaning in `parseHash` today, but giving the segment one
 * would make the router the second place that decides what this screen is.
 */
export function tabFromHash(hash: string): PolicyTab {
  const query = hash.split('?')[1];
  if (!query) return 'tumu';
  const value = new URLSearchParams(query).get('kural');
  return TABS.some((t) => t.id === value) ? (value as PolicyTab) : 'tumu';
}

export function hashForTab(tab: PolicyTab): string {
  return tab === 'tumu' ? '#/politikalar' : `#/politikalar?kural=${tab}`;
}

/** The tab the address asks for, kept in step with the back button. */
function useTabInHash(): [PolicyTab, (tab: PolicyTab) => void] {
  const [tab, setTab] = useState<PolicyTab>(() =>
    typeof window === 'undefined' ? 'tumu' : tabFromHash(window.location.hash),
  );

  useEffect(() => {
    const onChange = () => setTab(tabFromHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const choose = useCallback((next: PolicyTab) => {
    const hash = hashForTab(next);
    if (window.location.hash === hash) {
      setTab(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [tab, choose];
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
  const [tab, choose] = useTabInHash();
  /*
    Tools whose rule was changed while a filter was on.

    A filter that drops the row the moment you change it is a filter that makes the change
    look like it failed: press `Onay ister` on a row inside the `Otomatik` tab and the row
    you were reading disappears from under the cursor. These stay put, wearing the rule they
    moved to, until the tab changes or the table is reloaded. It is a ref rather than state
    because the redraw is already coming from `setRows` — this only decides what that redraw
    keeps.
  */
  const moved = useRef<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    moved.current.clear();
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

  // A tab is a fresh question; nothing is being held in place across it.
  useEffect(() => {
    moved.current.clear();
  }, [tab]);

  const change = async (row: ToolPolicy, mode: PolicyMode) => {
    if (row.mode === mode || busyTool) return;
    setBusyTool(row.toolName);
    setSaveError(null);
    moved.current.add(row.toolName);
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

  const counts = useMemo(() => {
    const out: Record<PolicyMode, number> = { auto: 0, ask: 0, forbidden: 0 };
    for (const row of rows ?? []) out[row.mode] += 1;
    return out;
  }, [rows]);

  const wanted = TABS.find((t) => t.id === tab)?.mode ?? null;
  const shown = useMemo(
    () =>
      (rows ?? []).filter(
        (row) => wanted == null || row.mode === wanted || moved.current.has(row.toolName),
      ),
    [rows, wanted],
  );

  const groups = useMemo(() => {
    const byProvider = new Map<string, ToolPolicy[]>();
    for (const row of shown) {
      const list = byProvider.get(row.provider);
      if (list) list.push(row);
      else byProvider.set(row.provider, [row]);
    }
    return [...byProvider.entries()]
      .sort((a, b) => providerRank(a[0]) - providerRank(b[0]) || a[0].localeCompare(b[0], 'tr'))
      .map(([provider, tools]) => ({ provider, tools }));
  }, [shown]);

  const changed = useMemo(
    () => (rows ?? []).filter((r) => r.mode !== defaultModeFor(r.risk)),
    [rows],
  );

  return (
    <div className="page">
      <div className="page__inner page__inner--app pol">
        <div className="page__head">
          <div className="page__head-text">
            {/*
              No paragraph under the title. It said "okuma otomatik çalışır, yazma onay
              ister" above a table that shows exactly that eighteen times, once per row,
              with the risk word beside the lit segment. A rule written twice is a rule
              the reader has to check against itself (#139).

              The one fact it carried that the table did not — that a mode starts from the
              tool's risk and can be moved off it — is on the rows where it is true: a tool
              running off its default says so, names the default it left, and offers the way
              back. Eighteen rows saying "varsayılan" to mark the nought or one that is not
              would be the same wallpaper in the other direction.
            */}
            <h1 className="t-title">Politikalar</h1>
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
            {/*
              The same three numbers, doing something. They used to be a strip of cells
              above the table — 12 / 6 / 0 with a sentence each — and answered a question
              nobody has: not "how many tools run unattended" but "which ones". On a tab
              the number still says how big the group is and now also gets you into it.

              Neutral, not amber. Amber on this product means a gate is holding something
              for you; twelve tools running at their own default are holding nothing.
            */}
            <TabStrip
              label="Kural listeleri"
              current={tab}
              onChoose={choose}
              tabs={TABS.map((t) => ({
                id: t.id,
                label: t.label,
                count: t.mode ? counts[t.mode] : null,
                hint: tabHint(t.mode),
              }))}
            />

            {changed.length > 0 && (
              <div className="notice notice--warn">
                <TriangleAlert size={16} aria-hidden />
                <span>
                  {changed.length} araç varsayılanından farklı çalışıyor:{' '}
                  {changed.map((r) => r.toolName).join(', ')}.
                </span>
              </div>
            )}

            {/*
              One table, not four cards. A provider is a band inside it — the
              step from canvas to panel is drawn once, at the outside edge, and
              everything under it is separated by a hairline. Four stacked cards
              spent ~70px on borders and gutters that said nothing.
            */}
            <div className="pol-table" role="tabpanel" id={`tabpanel-${tab}`} aria-labelledby={`tab-${tab}`}>
              {groups.map(({ provider, tools }) => {
                const meta = PROVIDERS[provider] ?? { title: provider, Icon: Plug };
                const headId = `pol-${provider}`;
                return (
                  <section className="pol-group" key={provider} aria-labelledby={headId}>
                    <div className="pol-group__head">
                      <span className="pol-group__icon" aria-hidden>
                        {/* Google covers two tools here, so it keeps the generic
                            glyph; the rest wear their own mark. */}
                        {providerOf(provider) ? (
                          <BrandMark provider={providerOf(provider)!} size={15} />
                        ) : (
                          <meta.Icon size={15} />
                        )}
                      </span>
                      <h2 className="t-title" id={headId}>
                        {meta.title}
                      </h2>
                      <span className="pol-group__n t-mono">{tools.length} araç</span>
                    </div>
                    <ul className="pol-list">
                      {tools.map((row) => (
                        <PolicyRow
                          key={row.toolName}
                          row={row}
                          busy={busyTool === row.toolName}
                          disabled={busyTool !== null && busyTool !== row.toolName}
                          // Held in a filtered list it no longer belongs to — see `moved`.
                          strayed={wanted != null && row.mode !== wanted}
                          onChange={(mode) => void change(row, mode)}
                        />
                      ))}
                    </ul>
                  </section>
                );
              })}
              {groups.length === 0 && (
                /*
                  An empty tab is the answer, not a gap. The forbidden one is the case that
                  is normally empty and the only one that needs a reason: no registered tool
                  carries the `silme` risk today, and the rule is still live — see the row
                  below, and the day such a tool is added it arrives forbidden.
                */
                <div className="pol-empty">
                  <EmptyState
                    Icon={tab === 'yasak' ? Ban : ShieldQuestion}
                    title={`${TABS.find((t) => t.id === tab)?.label} kuralında araç yok`}
                    description={
                      tab === 'yasak'
                        ? 'Kayıtlı hiçbir aracın riski silme değil. Kural boşta durmuyor: silme riskli bir araç eklendiği gün varsayılanı yasak gelir, aşağıdaki satır da her zaman yasaktır.'
                        : 'Bu kuralda çalışan araç yok. Bir aracın kuralını Tümü sekmesinden değiştirebilirsin.'
                    }
                  />
                </div>
              )}
            </div>

            {/* The rule that has no row of its own, because it is about the tools
                that are NOT in this table. Issue #14 asks for it explicitly.

                Not drawn under `Otomatik` or `Onay ister`: it is a permanently forbidden
                row, and a filter that says "these run unattended" must not have it in the
                panel underneath. */}
            {(tab === 'tumu' || tab === 'yasak') && (
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
            </section>
            )}
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
  /** The tab filters to another rule and this row is only still here because you moved it. */
  strayed?: boolean;
  onChange: (mode: PolicyMode) => void;
};

function PolicyRow({ row, busy, disabled, strayed = false, onChange }: RowProps) {
  const fallback = defaultModeFor(row.risk);
  const deviates = row.mode !== fallback;
  const risk = RISKS[row.risk];

  return (
    <li className={`pol-row${deviates ? ' pol-row--off' : ''}`}>
      {/*
        The scan column. Risk is what the mode has to be read against — "auto"
        on a read tool and "auto" on a write tool are not the same sentence —
        so it holds the fixed gutter, and the word beside it repeats the glyph
        rather than relying on colour.
      */}
      <span className={`pol-row__mark pol-row__mark--${row.risk}`} aria-hidden>
        <risk.Icon size={13} />
      </span>
      {/* Name and chip share the row's second column: the grid has four cells and a
          fifth child would push the segmented control out of its own. */}
      <span className="pol-row__id">
        <code className="pol-row__name t-mono">{row.toolName}</code>
        {/* The row you just changed does not vanish from under the cursor: it keeps its
            place in the list it has left, saying where it went, until the tab changes or
            the table is reloaded. A filter that drops the row on the press makes a change
            that worked look like one that failed. */}
        {strayed && <span className="pol-row__moved">→ {modeLabel(row.mode)}</span>}
      </span>
      <span className={`pol-row__risk pol-row__risk--${row.risk}`}>{risk.label}</span>

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

      {/*
        A row at its default says so three times over — the risk badge, the lit
        segment, and a sentence rebuilding both out of the same two words. Only
        the third one can be dropped without losing a fact, so it is: this line
        appears when the row has something the badge and the segment cannot say,
        namely that an operator moved it, or that a move is being saved.
      */}
      {(busy || deviates) && (
        <p className="pol-row__src">
          {busy ? (
            <span className="pol-row__saving">
              <RefreshCw size={12} aria-hidden className="spin" />
              Kaydediliyor…
            </span>
          ) : (
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
          )}
        </p>
      )}
    </li>
  );
}
