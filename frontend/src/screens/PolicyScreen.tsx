import {
  Ban,
  Eye,
  Hand,
  Pencil,
  RefreshCw,
  RotateCcw,
  ShieldQuestion,
  Trash2,
  TriangleAlert,
  Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { BrandMark, providerOf } from '../components/BrandMark';
import type { Provider } from '../components/BrandMark';
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

function modeLabel(mode: PolicyMode): string {
  return MODES.find((m) => m.key === mode)?.label ?? mode;
}

/* ------------------------------------------------------------------ */
/* The two questions the table is asked                                */
/* ------------------------------------------------------------------ */

/**
 * WHICH APP, and then WHICH RULE.
 *
 * <p>The tabs used to split by rule — `Tümü / Otomatik 12 / Onay ister 6 / Yasak`. Nobody
 * walks up to this screen holding the number twelve. They walk up holding an app: "what can
 * it do in Jira". So the app takes the tabs, wearing the app's own mark, and the rule
 * becomes a filter *inside* the selected tab — one chip row in the table's head, not a
 * second strip of tabs stacked on the first (#154).
 *
 * <p>`null` on either axis means "not filtered", and `Tümü` with no chip pressed is the
 * whole table: eighteen rules, every one of them on screen at once. That is the state the
 * height was cut to fit, because a rule table you have to scroll answers "what is this
 * agent allowed to do" worse than a dense one you can take in whole.
 */
type ProviderTab = 'tumu' | string;

/** Names for the providers we actually ship a tool for. */
const PROVIDER_LABEL: Record<Provider, string> = {
  jira: 'Jira',
  confluence: 'Confluence',
  gmail: 'Gmail',
  calendar: 'Takvim',
  docs: 'Doküman',
  github: 'GitHub',
  slack: 'Slack',
  notion: 'Notion',
};

/**
 * Tab order, fixed by hand on purpose.
 *
 * <p>WHICH tabs exist is derived from the rows — see `providerTabs` — so a provider with no
 * registered tool never gets one, and a provider added to the registry gets one for free.
 * The ORDER cannot be derived: sorting by group size would make the strip rearrange itself
 * every time somebody changes a rule, and a tab that moves is a tab you have to re-find.
 *
 * <p>Google is two families here, not one. `Gmail` and `Takvim` are separate tools with
 * separate risks and they were sharing a band called "Google — Gmail + Takvim", which is a
 * heading that answers neither question.
 */
const PROVIDER_ORDER: Provider[] = [
  'jira',
  'confluence',
  'gmail',
  'calendar',
  'docs',
  'github',
  'slack',
  'notion',
];

/**
 * The group a rule belongs to, read off the tool id.
 *
 * <p>`jira.createIssue` is already the answer; the row's `provider` field is not, because
 * the server calls both Gmail and Calendar `google`. A tool id we do not recognise keeps
 * its server-side provider so it still lands somewhere — it just gets a tab with no mark
 * rather than a borrowed one.
 */
function groupOf(row: ToolPolicy): string {
  return providerOf(row.toolName) ?? row.provider;
}

function groupLabel(group: string): string {
  return PROVIDER_LABEL[group as Provider] ?? group;
}

function groupMark(group: string, size: number) {
  const known = PROVIDER_ORDER.includes(group as Provider) ? (group as Provider) : null;
  return known ? <BrandMark provider={known} size={size} /> : null;
}

function groupRank(group: string): number {
  const index = PROVIDER_ORDER.indexOf(group as Provider);
  return index < 0 ? PROVIDER_ORDER.length : index;
}

/* ------------------------------------------------------------------ */
/* The address                                                         */
/* ------------------------------------------------------------------ */

/** `?kural=` names a mode; the ids are Turkish because the address is read by people. */
const MODE_PARAM: { id: string; mode: PolicyMode }[] = [
  { id: 'otomatik', mode: 'auto' },
  { id: 'onay', mode: 'ask' },
  { id: 'yasak', mode: 'forbidden' },
];

export type PolicyView = { provider: ProviderTab; mode: PolicyMode | null };

/**
 * Two queries, not a path segment, and the same shape Akışlar uses.
 *
 * <p>`#/politikalar/<x>` has no meaning in `parseHash` today, and giving the segment one
 * would make the router the second place that decides what this screen is. Both axes are
 * optional and anything unreadable falls back to "not filtered" — a link that has rotted
 * shows the whole table rather than an empty one.
 *
 * <p>`?kural=` is unchanged from when it selected a tab, so every link written before the
 * provider tabs existed still opens the rule it opened.
 */
export function viewFromHash(hash: string): PolicyView {
  const query = hash.split('?')[1];
  if (!query) return { provider: 'tumu', mode: null };
  const params = new URLSearchParams(query);
  const provider = params.get('saglayici');
  const kural = params.get('kural');
  return {
    provider: provider && provider !== 'tumu' ? provider : 'tumu',
    mode: MODE_PARAM.find((m) => m.id === kural)?.mode ?? null,
  };
}

export function hashForView(view: PolicyView): string {
  const parts: string[] = [];
  if (view.provider !== 'tumu') parts.push(`saglayici=${view.provider}`);
  const kural = MODE_PARAM.find((m) => m.mode === view.mode)?.id;
  if (kural) parts.push(`kural=${kural}`);
  return parts.length === 0 ? '#/politikalar' : `#/politikalar?${parts.join('&')}`;
}

/** What the address asks for, kept in step with the back button. */
function useViewInHash(): [PolicyView, (next: PolicyView) => void] {
  const [view, setView] = useState<PolicyView>(() =>
    typeof window === 'undefined'
      ? { provider: 'tumu', mode: null }
      : viewFromHash(window.location.hash),
  );

  useEffect(() => {
    const onChange = () => setView(viewFromHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const choose = useCallback((next: PolicyView) => {
    const hash = hashForView(next);
    if (window.location.hash === hash) {
      setView(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [view, choose];
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
  const [view, choose] = useViewInHash();
  /*
    Tools whose rule was changed while a filter was on.

    A filter that drops the row the moment you change it is a filter that makes the change
    look like it failed: press `Onay ister` on a row while the `Otomatik` chip is down and
    the row you were reading disappears from under the cursor. These stay put, wearing the
    rule they moved to, until the filter changes or the table is reloaded. It is a ref
    rather than state because the redraw is already coming from `setRows` — this only
    decides what that redraw keeps.
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

  // A new question; nothing is being held in place across it.
  useEffect(() => {
    moved.current.clear();
  }, [view.provider, view.mode]);

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

  /** Every provider that has at least one registered tool, in the fixed order. */
  const providerTabs = useMemo(() => {
    const size = new Map<string, number>();
    for (const row of rows ?? []) {
      const group = groupOf(row);
      size.set(group, (size.get(group) ?? 0) + 1);
    }
    return [...size.entries()]
      .sort((a, b) => groupRank(a[0]) - groupRank(b[0]) || a[0].localeCompare(b[0], 'tr'))
      .map(([group, n]) => ({ group, n }));
  }, [rows]);

  /** Everything the selected provider tab holds, before the rule chips narrow it. */
  const inTab = useMemo(
    () => (rows ?? []).filter((row) => view.provider === 'tumu' || groupOf(row) === view.provider),
    [rows, view.provider],
  );

  /*
    The three numbers that used to be a 12 / 6 / 0 strip above the table, and then the
    counts on the rule tabs. They count what is in the tab you are in — on Jira the chip
    says how many Jira tools ask, which is the question the tab put you there to ask.
  */
  const counts = useMemo(() => {
    const out: Record<PolicyMode, number> = { auto: 0, ask: 0, forbidden: 0 };
    for (const row of inTab) out[row.mode] += 1;
    return out;
  }, [inTab]);

  const shown = useMemo(
    () =>
      inTab.filter(
        (row) => view.mode == null || row.mode === view.mode || moved.current.has(row.toolName),
      ),
    [inTab, view.mode],
  );

  /* Sorted by provider then by name, so `Tümü` still reads as five blocks even though the
     band headings are gone — the tool id is what carries the provider down the column. */
  const listed = useMemo(
    () =>
      [...shown].sort(
        (a, b) =>
          groupRank(groupOf(a)) - groupRank(groupOf(b)) ||
          groupOf(a).localeCompare(groupOf(b), 'tr') ||
          a.toolName.localeCompare(b.toolName, 'tr'),
      ),
    [shown],
  );

  const changed = useMemo(
    () => (rows ?? []).filter((r) => r.mode !== defaultModeFor(r.risk)),
    [rows],
  );

  const tabLabel = view.provider === 'tumu' ? 'Tümü' : groupLabel(view.provider);
  /* The rule that covers the tools that are NOT in this table. It belongs to no provider,
     so it is drawn under `Tümü`; and it is a permanently forbidden row, so a chip claiming
     "these run unattended" must not have it in the panel underneath. */
  const showUnknownRule =
    view.provider === 'tumu' && (view.mode == null || view.mode === 'forbidden');

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
            <div className="skeleton" style={{ height: 44 }} />
            <div className="skeleton" style={{ height: 320, opacity: 0.6 }} />
          </>
        )}

        {!loading && rows && (
          <>
            {/*
              An app per tab, wearing the app's own mark. The list is derived from the tool
              ids that actually loaded, so it cannot name a provider we have no tool for,
              and a provider added to the registry appears here without anyone editing a
              list. The mark is the provider's trademark used referentially — see BrandMark.

              Neutral counters, no `tone` prop. Amber on this product means a gate is
              holding something for you; seven Jira rules are holding nothing.
            */}
            <TabStrip
              label="Sağlayıcılar"
              current={view.provider}
              onChoose={(provider) => choose({ ...view, provider })}
              tabs={[
                { id: 'tumu', label: 'Tümü', count: rows.length, hint: 'kayıtlı her araç' },
                ...providerTabs.map(({ group, n }) => ({
                  id: group,
                  label: groupLabel(group),
                  count: n,
                  icon: groupMark(group, 14),
                  hint: `${groupLabel(group)} araçları`,
                })),
              ]}
            />

            {/*
              One frame, no cards, no band headings. A provider used to be a band inside the
              table with its own head and its own count; both moved onto the tab, which is
              where you now pick the provider. Keeping them would have printed the provider
              three times over — tab, band head, and the `jira.` in front of every id — and
              cost 116px of the 306 the page had to give back (#154).
            */}
            <div
              className="pol-table"
              role="tabpanel"
              id={`tabpanel-${view.provider}`}
              aria-labelledby={`tab-${view.provider}`}
            >
              {/*
                The column head, and the filter that lives inside the tab. Left: what you
                are looking at and how big it is. Right: the rule chips — press one to
                narrow, press it again to let go. Chips and not a second tab strip on
                purpose: two stacked strips make the reader guess which one is the list and
                which one is the filter.
              */}
              <div className="pol-table__head">
                {/*
                  Nothing on the left but the alarm. The provider's name, its mark and its
                  count are on the tab you pressed to get here, and the panel underneath a
                  tab does not need to repeat what the tab says — that is how the old band
                  heads got to be 116px of wallpaper.

                  The banner this alarm replaced listed the strayed tool names above the
                  table and cost ~56px the moment anything deviated, on a screen whose
                  whole complaint was that it scrolled. The count stays and is visible from
                  every tab, the names are one hover away, and every strayed row still
                  carries its own amber edge, "Operatör değiştirdi" and the way back (#67).
                */}
                <span className="pol-table__what">
                  {changed.length > 0 && (
                    <span
                      className="pol-table__off"
                      title={changed.map((r) => r.toolName).join(', ')}
                    >
                      <TriangleAlert size={12} aria-hidden />
                      {changed.length} araç varsayılan dışı
                    </span>
                  )}
                </span>
                <div className="pol-modes" role="group" aria-label="Kural filtresi">
                  {/* The chips head the column they filter, so they need a word saying
                      they are a filter and not three buttons that set every row at once. */}
                  <span className="pol-modes__label">Kural</span>
                  {MODES.map((mode) => {
                    const on = view.mode === mode.key;
                    return (
                      <button
                        key={mode.key}
                        type="button"
                        className={`pol-chip${on ? ` pol-chip--on pol-chip--${mode.key}` : ''}`}
                        aria-pressed={on}
                        title={on ? 'Kural filtresini kaldır' : mode.hint}
                        onClick={() => choose({ ...view, mode: on ? null : mode.key })}
                      >
                        <mode.Icon size={13} aria-hidden />
                        {mode.label}
                        {counts[mode.key] > 0 && (
                          <span className="pol-chip__n t-mono">{counts[mode.key]}</span>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>

              {listed.length > 0 && (
                <ul className="pol-list">
                  {listed.map((row) => (
                    <PolicyRow
                      key={row.toolName}
                      row={row}
                      busy={busyTool === row.toolName}
                      disabled={busyTool !== null && busyTool !== row.toolName}
                      // Held in a filtered list it no longer belongs to — see `moved`.
                      strayed={view.mode != null && row.mode !== view.mode}
                      onChange={(mode) => void change(row, mode)}
                    />
                  ))}
                </ul>
              )}

              {listed.length === 0 && (
                /*
                  An empty filter is the answer, not a gap. `Yasak` is the case that is
                  normally empty and the only one that needs a reason: no registered tool
                  carries the `silme` risk today, and the rule is still live — see the row
                  below, and the day such a tool is added it arrives forbidden.
                */
                <div className="pol-empty">
                  <EmptyState
                    Icon={view.mode === 'forbidden' ? Ban : ShieldQuestion}
                    title={emptyTitle(view, tabLabel)}
                    description={
                      view.mode === 'forbidden'
                        ? 'Kayıtlı hiçbir aracın riski silme değil. Kural boşta durmuyor: silme riskli bir araç eklendiği gün varsayılanı yasak gelir, listede olmayan her araç da her zaman yasaktır.'
                        : 'Bu kuralda çalışan araç yok. Kural filtresini kaldırınca bu sekmedeki bütün araçlar geri gelir.'
                    }
                  />
                </div>
              )}

              {/* The rule that has no row of its own, because it is about the tools that
                  are NOT in this table. Issue #14 asks for it explicitly. It used to be a
                  dashed card below the table, worth 117px plus the gap above it; it is the
                  table's last row now, which is also what it is. */}
              {showUnknownRule && (
                <div className="pol-rule" aria-label="Listede olmayan her araç">
                  <span className="pol-row__mark pol-row__mark--destructive" aria-hidden>
                    <ShieldQuestion size={13} />
                  </span>
                  <span className="pol-row__id">
                    <code className="pol-row__name t-mono">bilinmeyen araç adı</code>
                  </span>
                  <span className="pol-row__risk pol-row__risk--destructive">bilinmiyor</span>
                  <span className="pol-fixed">
                    <Ban size={13} aria-hidden />
                    Yasak — değiştirilemez
                  </span>
                  <p className="pol-row__src">
                    Listede olmayan her araç yasaktır: bir plan bu tabloda geçmeyen bir araç
                    adı üretirse motor onu çalıştırmadan reddeder (
                    <code className="t-mono">unknown tool</code>). Kayıtlı araç yoksa risk de
                    bilinmiyordur; bilinmeyen riskin varsayılanı en dar olanıdır.
                  </p>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/** What the empty frame is empty of — both halves of the question, when both are asked. */
function emptyTitle(view: PolicyView, tabLabel: string): string {
  const rule = view.mode ? modeLabel(view.mode) : null;
  if (rule && view.provider !== 'tumu') return `${tabLabel} altında ${rule} kuralında araç yok`;
  if (rule) return `${rule} kuralında araç yok`;
  return `${tabLabel} altında araç yok`;
}

type RowProps = {
  row: ToolPolicy;
  busy: boolean;
  disabled: boolean;
  /** The chips filter to another rule and this row is only still here because you moved it. */
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

        Not the provider's mark: inside the Jira tab that would be seven identical
        logos, and on `Tümü` the `jira.` in front of the id already says it. A mark
        earns its place where it tells two things apart — on the tabs.
      */}
      <span className={`pol-row__mark pol-row__mark--${row.risk}`} aria-hidden>
        <risk.Icon size={13} />
      </span>
      {/* Name and chip share the row's second column: the grid has four cells and a
          fifth child would push the segmented control out of its own. */}
      <span className="pol-row__id">
        <code className="pol-row__name t-mono">{row.toolName}</code>
        {/* The row you just changed does not vanish from under the cursor: it keeps its
            place in the list it has left, saying where it went, until the filter changes or
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
