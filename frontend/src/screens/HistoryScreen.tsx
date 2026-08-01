import { ArrowRight, History, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { StatusPill } from '../components/StatusPill';
import { getRunSource } from '../data';
import { formatRelative, formatTokens, formatUsd } from '../lib/format';
import type { RunSummary } from '../types/api';
import '../styles/screens.css';

type Props = { onOpen: (runId: string) => void };

/** The one status that is waiting on a person rather than on the machine. */
const WAITING = 'awaiting_approval';

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

export function HistoryScreen({ onOpen }: Props) {
  const [rows, setRows] = useState<RunSummary[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const [parked, setParked] = useState<RunSummary[] | null>(null);

  /*
    Two requests, because they answer different questions.

    The list is a page of history — twenty rows, newest first. The waiting block is a set:
    the top bar counts every run stopped on a person and sends the reader here, and live it
    said 29 while this screen showed the 3 that happened to fall on that page. The other 26
    had no route in the product at all. Asking the server for the status is one request and
    cannot race with a run finishing between pages.

    The answer is filtered again here rather than trusted: a server that does not know the
    parameter answers with the ordinary page, and the block would then claim finished runs
    are waiting on a decision. Filtering costs nothing and makes the heading true whatever
    comes back.
  */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const source = getRunSource();
      const [page, waitingRows] = await Promise.all([
        source.listRuns(),
        source.listRuns({ status: 'awaiting_approval', size: 100 }).catch(() => null),
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

  const { waiting: onPage, settled } = useMemo(() => splitByDecision(rows ?? []), [rows]);
  const waiting = parked ? parked.filter((row) => row.status === WAITING) : onPage;
  const repeated = useMemo(() => repeatedGoals(rows ?? []), [rows]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Geçmiş</h1>
            <p className="t-caption">
              Çalışmış akışlar. Birine tıkla — adımlar, parametreler ve ajan mesajlarıyla tam
              denetim izi açılır.
            </p>
          </div>
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        {error != null && <LoadError error={error} onRetry={() => void load()} />}

        {loading && !rows && <div className="skeleton" style={{ height: 320 }} />}

        {!loading && rows && rows.length === 0 && (
          <EmptyState
            Icon={History}
            title="Henüz çalışmış akış yok"
            description="İlk işini sohbet ekranından ver; biten her akış buraya denetim iziyle düşer."
          />
        )}

        {/*
          Waiting runs come out of the list and sit above it. Buried among finished
          rows — same frame, same weight, same height — the one thing the product is
          holding for a human was the hardest thing on the screen to find.
        */}
        {waiting.length > 0 && (
          <section className="runs runs--waiting" aria-labelledby="runs-waiting-h">
            <div className="runs__head">
              <h2 className="t-label" id="runs-waiting-h">
                Onayını bekleyen {waiting.length} çalıştırma
              </h2>
              <span className="t-caption">Bu akışlar durdu; devam etmesi senin kararına bağlı.</span>
            </div>
            <ul className="runs__list">
              {waiting.map((row) => (
                <RunRow
                  key={row.id}
                  row={row}
                  withId={repeated.has(row.goal)}
                  action="Karar ver"
                  onOpen={onOpen}
                />
              ))}
            </ul>
          </section>
        )}

        {settled.length > 0 && (
          <section className="runs" aria-labelledby="runs-done-h">
            <div className="runs__head">
              <h2 className="t-label" id="runs-done-h">
                Çalışmış akışlar
              </h2>
              <span className="t-caption">{settled.length} kayıt</span>
            </div>
            <ul className="runs__list">
              {settled.map((row) => (
                <RunRow key={row.id} row={row} withId={repeated.has(row.goal)} onOpen={onOpen} />
              ))}
            </ul>
          </section>
        )}
      </div>
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
  return (
    <li className="run-row">
      <button
        type="button"
        className="run-row__btn"
        onClick={() => onOpen(row.id)}
        aria-label={action ? `${row.goal} — ${action}` : row.goal}
      >
        <span className="run-row__main">
          <span className="run-row__goal">{row.goal}</span>
          <span className="run-row__meta">
            <span>{formatRelative(row.createdAt)}</span>
            {withId && <code className="t-mono">#{shortId(row.id)}</code>}
            <span>{row.stepCount} adım</span>
            <span>{formatTokens(row.costTokens)} token</span>
            <span>{formatUsd(row.costUsd)}</span>
          </span>
        </span>
        {action && <span className="run-row__action">{action}</span>}
        <StatusPill status={row.status} />
        <ArrowRight size={16} aria-hidden className="run-row__chevron" />
      </button>
    </li>
  );
}
