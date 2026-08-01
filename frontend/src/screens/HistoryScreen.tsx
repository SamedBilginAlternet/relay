import { History, ShieldQuestion, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { getRunSource } from '../data';
import { formatRelative, formatTokens, formatUsd } from '../lib/format';
import { runStatusMeta } from '../lib/status';
import type { RunSummary } from '../types/api';
import '../styles/screens.css';

type Props = { onOpen: (runId: string) => void };

/** The one status that is waiting on a person rather than on the machine. */
const WAITING = 'awaiting_approval';

/** How many rows one visit asks for. The server caps a page at 100. */
const PAGE = 100;

/**
 * Which list is on screen.
 *
 * <p>They used to be stacked — the decision queue first, the log under it — and on the live
 * box that is 28 parked runs, about a thousand pixels, standing between the top of the
 * screen and the first finished one. Neither reader was served: the one who came to decide
 * had to read past a heading they did not want, and the one who came to look something up
 * had to scroll the whole queue to reach it.
 *
 * <p>A filter, not a partition. `tumu` is every run in the page including the parked ones,
 * in the order the server sent; `bekleyen` is the subset stopped on a person. Splitting
 * them into two disjoint lists is what made the two counts impossible to reconcile — one
 * was a page size and the other a total — and it took parked runs out of the chronology
 * they belong to.
 */
export type HistoryTab = 'tumu' | 'bekleyen';

/**
 * The tab named in the address, or nothing.
 *
 * <p>It lives in the query rather than in a path segment because `parseHash` reads
 * `#/history/<id>` as a run detail — a tab there would be a run id that does not exist.
 * The query is dropped before routing, so this is invisible to the router and cannot
 * become a second definition of what `#/history` means.
 */
export function tabFromHash(hash: string): HistoryTab {
  const query = hash.split('?')[1];
  if (!query) return 'tumu';
  return new URLSearchParams(query).get('durum') === 'bekleyen' ? 'bekleyen' : 'tumu';
}

export function hashForTab(tab: HistoryTab): string {
  return tab === 'bekleyen' ? '#/history?durum=bekleyen' : '#/history';
}

/**
 * The runs that stopped for a decision, and the rest.
 *
 * <p>Both keep the order the server sent (newest first); only the grouping is new.
 * A run that is waiting is the single thing on this screen that costs the reader
 * something to miss — it is not a row like the others and it is not sorted like one.
 */
export function splitByDecision(rows: RunSummary[]): {
  waiting: RunSummary[];
  settled: RunSummary[];
} {
  return {
    waiting: rows.filter((row) => row.status === WAITING),
    settled: rows.filter((row) => row.status !== WAITING),
  };
}

/**
 * Goals that appear more than once in the list.
 *
 * <p>The same prompt gets run again all the time ("KAN projesindeki açık kayıtları
 * listele"), and two identical titles a minute apart are indistinguishable. Those
 * rows — and only those — also carry the run's short id, so there is something to
 * name them by.
 */
export function repeatedGoals(rows: RunSummary[]): Set<string> {
  const seen = new Map<string, number>();
  for (const row of rows) seen.set(row.goal, (seen.get(row.goal) ?? 0) + 1);
  return new Set([...seen.entries()].filter(([, count]) => count > 1).map(([goal]) => goal));
}

function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 6);
}

/**
 * The tab the address asks for, kept in step with the back button.
 *
 * <p>Held in the URL rather than in state alone: a tab that only exists in memory cannot
 * be linked to, does not survive a refresh, and swallows Back — the reader presses it
 * expecting the other list and leaves the screen instead.
 */
function useTabInHash(): [HistoryTab, (tab: HistoryTab) => void] {
  const [tab, setTab] = useState<HistoryTab>(() =>
    typeof window === 'undefined' ? 'tumu' : tabFromHash(window.location.hash),
  );

  useEffect(() => {
    const onChange = () => setTab(tabFromHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const choose = useCallback((next: HistoryTab) => {
    const hash = hashForTab(next);
    if (window.location.hash === hash) {
      setTab(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [tab, choose];
}

export function HistoryScreen({ onOpen }: Props) {
  const [rows, setRows] = useState<RunSummary[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const [parked, setParked] = useState<RunSummary[] | null>(null);
  const [tab, choose] = useTabInHash();

  /*
    Two requests, because they answer different questions.

    The list is a page of history — newest first. The waiting set is a set: the sidebar
    badge counts every run stopped on a person and sends the reader here, and live it said
    29 while this screen showed the 3 that happened to fall on that page. The other 26 had
    no route in the product at all. Asking the server for the status is one request and
    cannot race with a run finishing between pages.

    The answer is filtered again here rather than trusted: a server that does not know the
    parameter answers with the ordinary page, and the tab would then claim finished runs
    are waiting on a decision. Filtering costs nothing and makes the count true whatever
    comes back.
  */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const source = getRunSource();
      const [page, waitingRows] = await Promise.all([
        source.listRuns({ size: PAGE }),
        source.listRuns({ status: 'awaiting_approval', size: PAGE }).catch(() => null),
      ]);
      setRows(page);
      setParked(waitingRows);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const all = rows ?? [];
  const onPage = useMemo(() => splitByDecision(all).waiting, [all]);
  const waiting = useMemo(
    () => (parked ? parked.filter((row) => row.status === WAITING) : onPage),
    [parked, onPage],
  );
  /*
    Counted over what is drawn, not over the page that was fetched. The waiting
    list comes from its own request (#100) and is usually the longer of the
    two, so a prompt run fifteen times and parked fifteen times appeared as
    fifteen identical rows — same goal, same age, same token count — while the
    id that tells them apart was suppressed for being "unique on the page".
  */
  const repeated = useMemo(() => repeatedGoals([...waiting, ...all]), [waiting, all]);

  const shown = tab === 'bekleyen' ? waiting : all;
  const nothingAtAll = !loading && rows != null && all.length === 0 && waiting.length === 0;

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Akışlar</h1>
            <p className="t-caption">
              Çalışmış ve karar bekleyen işler. Birine tıkla — adımlar, parametreler ve ajan
              mesajlarıyla tam denetim izi açılır.
            </p>
          </div>
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        {error != null && <LoadError error={error} onRetry={() => void load()} />}

        {loading && !rows && <div className="skeleton" style={{ height: 320 }} />}

        {nothingAtAll && (
          <EmptyState
            Icon={History}
            title="Henüz çalışmış akış yok"
            description="İlk işini sohbet ekranından ver; biten her akış buraya denetim iziyle düşer."
          />
        )}

        {!nothingAtAll && rows != null && (
          <>
            {/*
              Two destinations, not two sections. Only the chosen list is built, so the
              rows of the other are not in the document at all — the reason the tabs exist
              is that 28 rows of one were standing on top of the other.

              The selected tab is marked in ink, not in violet. If violet meant "the tab
              you are on" it would stop meaning "this is waiting on you", which is the one
              thing it means everywhere else in the product.
            */}
            <div className="tabs" role="tablist" aria-label="Akış listeleri">
              <TabButton id="tumu" current={tab} onChoose={choose} label="Tümü" />
              <TabButton
                id="bekleyen"
                current={tab}
                onChoose={choose}
                label="Onay bekleyen"
                count={waiting.length}
              />
            </div>

            <section
              className={`runs${tab === 'bekleyen' ? ' runs--waiting' : ''}`}
              role="tabpanel"
              id={`tabpanel-${tab}`}
              aria-labelledby={`tab-${tab}`}
            >
              {shown.length === 0 ? (
                /* Neutral, never violet: an empty decision queue is a good outcome and
                   must not be drawn as an alert. */
                <EmptyState
                  Icon={tab === 'bekleyen' ? ShieldQuestion : History}
                  title={tab === 'bekleyen' ? 'Onay bekleyen akış yok' : 'Bu sayfada kayıt yok'}
                  description={
                    tab === 'bekleyen'
                      ? 'Bir akış yazma yetkisi isteyince burada durur ve kenar çubuğunda sayılır.'
                      : 'Biten her akış buraya denetim iziyle düşer.'
                  }
                />
              ) : (
                <>
                  {/* What the list is a list of. `N kayıt` used to sit here and it was a
                      page size wearing the clothes of a total: the server holds 182 runs
                      and the caption said 20. */}
                  <p className="runs__note">
                    {tab === 'bekleyen'
                      ? `Bu ${shown.length} akış durdu; devam etmesi senin kararına bağlı.`
                      : `En yeni ${shown.length} çalıştırma, yeniden eskiye.`}
                  </p>
                  <div className="runs__frame">
                    <ColumnHeads last={tab === 'bekleyen' ? 'Karar' : 'Durum'} />
                    <ul className="runs__list">
                      {shown.map((row) => (
                        <RunRow
                          key={row.id}
                          row={row}
                          withId={repeated.has(row.goal)}
                          action={tab === 'bekleyen' ? 'Karar ver' : undefined}
                          onOpen={onOpen}
                        />
                      ))}
                    </ul>
                  </div>
                </>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * One tab, and the size of what is behind it.
 *
 * <p>Only the decision queue carries a count. The other tab holds a page, not a corpus,
 * and printing the page size next to a real total invites the reader to subtract them.
 */
function TabButton({
  id,
  current,
  onChoose,
  label,
  count,
}: {
  id: HistoryTab;
  current: HistoryTab;
  onChoose: (tab: HistoryTab) => void;
  label: string;
  count?: number;
}) {
  const selected = current === id;
  return (
    <button
      type="button"
      role="tab"
      id={`tab-${id}`}
      aria-selected={selected}
      aria-controls={`tabpanel-${id}`}
      className={`tab${selected ? ' tab--on' : ''}`}
      onClick={() => onChoose(id)}
    >
      {label}
      {count != null && count > 0 && <span className="tab__count t-mono">{count}</span>}
    </button>
  );
}

/**
 * The names of the machine columns, written once at the top of the list.
 *
 * <p>Every row used to carry its own units — `4 adım`, `4.246 token` — which is
 * the same three words printed forty-five times and the reason the numbers had
 * to be packed left instead of aligned. Naming the column once buys the row a
 * bare, right-aligned figure that lines up with the one above it.
 *
 * <p>Presentation only: it is a `div`, not a table head and not a list item, so
 * the lists on this screen stay lists of runs.
 */
function ColumnHeads({ last }: { last: string }) {
  return (
    <div className="runs__cols" aria-hidden>
      <span />
      <span />
      <span className="t-label">Zaman</span>
      <span className="t-label">Adım</span>
      <span className="t-label">Token</span>
      <span className="t-label">Tutar</span>
      <span className="t-label">{last}</span>
    </div>
  );
}

function RunRow({
  row,
  withId,
  action,
  onOpen,
}: {
  row: RunSummary;
  withId: boolean;
  action?: string;
  onOpen: (runId: string) => void;
}) {
  const status = runStatusMeta(row.status);
  const Icon = status.Icon;
  /*
    The button's own label replaces everything inside it for a screen reader, so
    the facts the columns carry have to be spelled out here — with the units the
    row no longer prints, because a bare "3" read out loud is not a step count.
    It also survives the two columns the narrow layout drops.
  */
  const label = [
    `${row.goal} — ${action ?? status.label}`,
    formatRelative(row.createdAt),
    `${row.stepCount} adım`,
    `${formatTokens(row.costTokens)} token`,
    formatUsd(row.costUsd),
  ].join(', ');
  return (
    <li className="run-row">
      <button type="button" className="run-row__btn" onClick={() => onOpen(row.id)} aria-label={label}>
        {/*
          The left gutter is the only column the eye has to walk to find a
          failure in a page of finished runs, so the mark stays in it whatever
          the row says on the right. Colour is never the whole signal: the glyph
          differs per status and the button's label spells it out.
        */}
        <span className={`run-row__mark ${status.className}`} aria-hidden>
          <Icon size={14} />
        </span>
        <span className="run-row__goal">
          {/* The goal is what gives way when the column is short — the id is the
              only thing telling two runs of the same prompt apart, so it never
              leaves the row with it. */}
          <span className="run-row__text">{row.goal}</span>
          {withId && <code className="run-row__id t-mono">#{shortId(row.id)}</code>}
        </span>
        {/* `display: contents` on wide screens, so these four sit in the row's
            own grid and line up with the heads; a wrapped strip under the goal
            once the columns no longer fit. */}
        <span className="run-row__nums">
          <span className="run-row__num t-mono">{formatRelative(row.createdAt)}</span>
          <span className="run-row__num t-mono">{row.stepCount}</span>
          <span className="run-row__num t-mono">
            {formatTokens(row.costTokens)}
          </span>
          <span className="run-row__num t-mono">{formatUsd(row.costUsd)}</span>
        </span>
        {/* Every run in the waiting tab carries the same status, so the last
            column says the thing that is not already in the tab: what the
            reader is being asked to do. */}
        <span className={`run-row__end${action ? ' run-row__end--act' : ` ${status.className}`}`}>
          {action ?? status.label}
        </span>
      </button>
    </li>
  );
}
