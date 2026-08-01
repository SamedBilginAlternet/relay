import { History, ShieldQuestion, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { RunCards } from '../components/RunCards';
import { TabStrip } from '../components/TabStrip';
import { getRunSource } from '../data';
import type { RunSummary } from '../types/api';
import '../styles/screens.css';

/*
  The list is imported, not lazily loaded, and that is the whole of what #156's
  chunk was for.

  ag-grid arrived in its own `React.lazy` chunk because it was 232KB gzipped —
  larger than the entire rest of this product put together — and the chat
  screen and the landing page had no business paying for a table they do not
  have. The list is cards now (see components/RunCards.tsx): the filtering, the
  sorting and the paging it kept are a few hundred lines in this app's own
  bundle, so the chunk, the Suspense boundary and the second loading state that
  came with them are all gone with it.
*/

type Props = { onOpen: (runId: string) => void };

/** The one status that is waiting on a person rather than on the machine. */
const WAITING = 'awaiting_approval';

/** How many rows one request asks for. The server caps a page at 100. */
const PAGE = 100;

/**
 * How many of those requests one visit is allowed to make.
 *
 * <p>The table pages through what is in hand, so what is in hand has to be the log and not
 * its first page: live the server holds 222 runs, one request answered with 100, and a
 * pager over those 100 would print `1 – 13 / 100` — a page size wearing the clothes of a
 * total, which is the exact bug the note line under the tabs was written to fix (#124).
 *
 * <p>Five is a ceiling, not a page count: the walk stops the moment a short page says the
 * log is exhausted, so the live box costs three requests and a fresh one costs a single
 * request. What the ceiling buys is that a box with fifty thousand runs cannot turn one
 * visit into five hundred requests — and when it is hit, the note says so in words rather
 * than letting the pager imply the log ends at row 500.
 */
const MAX_REQUESTS = 5;

/**
 * The whole log, or as much of it as the ceiling allows.
 *
 * <p>Two ways to stop, and both matter. A page shorter than the one asked for means the
 * server has no more rows — the ordinary end. A page that adds nothing new means the
 * server does not understand `page` and is answering every request with the same first
 * hundred; walking on would stack five copies of it and report 500 runs that do not
 * exist. Neither case is treated as an error: what came back is what there is.
 */
export async function walkRuns(
  listRuns: (options: { status?: RunSummary['status']; size: number; page: number }) => Promise<
    RunSummary[]
  >,
  status?: RunSummary['status'],
): Promise<{ rows: RunSummary[]; complete: boolean }> {
  const rows: RunSummary[] = [];
  const seen = new Set<string>();
  for (let page = 0; page < MAX_REQUESTS; page += 1) {
    const batch = await listRuns({ status, size: PAGE, page });
    const fresh = batch.filter((row) => !seen.has(row.id));
    for (const row of fresh) seen.add(row.id);
    rows.push(...fresh);
    if (batch.length < PAGE || fresh.length === 0) return { rows, complete: true };
  }
  return { rows, complete: false };
}

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
  const [truncated, setTruncated] = useState(false);
  const [tab, choose] = useTabInHash();

  /*
    Two requests, because they answer different questions.

    The list is a page of history — newest first. The waiting set is a set: the waiting
    badge counted every run stopped on a person and sent the reader here, and live it said
    29 while this screen showed the 3 that happened to fall on that page. The other 26 had
    no route in the product at all. Asking the server for the status is one request and
    cannot race with a run finishing between pages.

    The answer is filtered again here rather than trusted: a server that does not know the
    parameter answers with the ordinary page, and the tab would then claim finished runs
    are waiting on a decision. Filtering costs nothing and makes the count true whatever
    comes back.

    Both are walked to the end rather than read one page deep (#163). The table pages
    through what is in hand, so what is in hand has to be the log: with 122 of the live
    box's 222 runs behind the first page, a pager over that page would move inside the
    newest hundred and quietly present it as the whole history.
  */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const source = getRunSource();
      const list = (options: { status?: RunSummary['status']; size: number; page: number }) =>
        source.listRuns(options);
      const [log, waitingRows] = await Promise.all([
        walkRuns(list),
        walkRuns(list, WAITING).catch(() => null),
      ]);
      setRows(log.rows);
      setTruncated(!log.complete);
      setParked(waitingRows ? waitingRows.rows : null);
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

  /*
    How many runs a filter is currently hiding.

    A list that quietly drops half its cards because a box is filled in above
    it is how a reader concludes the data is gone. The count above the list is
    the one line on screen that can say otherwise, so it says it — and it is
    reset whenever the list underneath changes, because a leftover "12 / 40"
    against a different list is worse than none.
  */
  const [hiddenBy, setHiddenBy] = useState<{ shownRows: number; total: number } | null>(null);
  useEffect(() => setHiddenBy(null), [tab]);
  const onFilterChange = useCallback(
    (shownRows: number, total: number) =>
      setHiddenBy(shownRows === total ? null : { shownRows, total }),
    [],
  );

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

        {/* Sized to what replaces it — the filter row and five two-row cards —
            so the pager does not leap up the screen when the list lands. */}
        {loading && !rows && <div className="skeleton" style={{ height: 480 }} />}

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
            <TabStrip
              label="Akış listeleri"
              current={tab}
              onChoose={choose}
              tone="gate"
              tabs={[
                { id: 'tumu', label: 'Tümü' },
                { id: 'bekleyen', label: 'Onay bekleyen', count: waiting.length },
              ]}
            />

            <section
              className="runs"
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
                      and the caption said 20.

                      `En yeni N` was the honest form of that while the screen only ever
                      read one page. It walks the log now, so the number is the log — and
                      the qualifier goes, because "the newest 222 of 222" is a hedge
                      against nothing. It comes back the moment the walk was cut short by
                      its own ceiling, which is the one case where there is more history
                      than this list holds and the pager cannot say so.

                      It is also where a filter has to own up to what it is hiding —
                      announced, because the cards it removed vanish without a word for
                      anyone not looking at the box that removed them. */}
                  <p className="runs__note" aria-live="polite">
                    {hiddenBy
                      ? `${hiddenBy.total} kayıttan ${hiddenBy.shownRows} tanesi gösteriliyor — filtre etkin.`
                      : tab === 'bekleyen'
                        ? `Bu ${shown.length} akış durdu; devam etmesi senin kararına bağlı.`
                        : truncated
                          ? `En yeni ${shown.length} çalıştırma, yeniden eskiye — daha eskisi bu listede yok.`
                          : `${shown.length} çalıştırma, yeniden eskiye.`}
                  </p>
                  {/* No frame around the list. A panel holding a column of
                      panels is the card-in-a-card the log spent #124 getting
                      out of; the cards are the surface now, and they sit on
                      the canvas like every other list of objects. */}
                  <RunCards
                    rows={shown}
                    repeated={repeated}
                    onOpen={onOpen}
                    action={tab === 'bekleyen' ? 'Karar ver' : undefined}
                    waiting={tab === 'bekleyen'}
                    onFilterChange={onFilterChange}
                  />
                </>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}
