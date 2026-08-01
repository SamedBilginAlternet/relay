import { motion, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getRunSource } from '../data';
import { formatRelative } from '../lib/format';
import { enterProps } from '../lib/motion';
import { runStatusMeta } from '../lib/status';
import type { Run, RunSummary } from '../types/api';
import '../styles/rail.css';

/**
 * The statuses that mean "this flow is not over".
 *
 * <p>`planning` and `running` are waiting on the machine; `awaiting_approval` is waiting on
 * a person. Everything else — done, failed, cancelled — belongs to Geçmiş, which is the
 * screen for things that already happened.
 */
export const LIVE_STATUSES: RunSummary['status'][] = ['awaiting_approval', 'running', 'planning'];

/** The one status where the flow has stopped and a human is the thing it is stopped on. */
const WAITING = 'awaiting_approval';

/**
 * Deliberately larger than the history page. The default page is twenty rows and the live
 * box had 28 runs parked on a decision on 2026-08-01 — a rail sized to that page would have
 * hidden a quarter of them and looked complete while doing it.
 */
const PAGE = 200;

/** Slow, and only while the tab is on screen. The stream is the mechanism; this is a net. */
const IDLE_REFRESH_MS = 60_000;

/** What one row needs. `done` is null when nobody has counted the finished steps. */
export type RailRun = {
  id: string;
  goal: string;
  status: string;
  stepCount: number;
  done: number | null;
  createdAt: string;
};

/**
 * Live runs, newest first, with the ones waiting on a person lifted to the top.
 *
 * <p>The lift is the whole ordering rule: a decision the user owes is worth more than
 * progress they owe nothing to. Inside each group the server's own order stands — newest
 * first — because "what did I just start" is the second question after "what is stuck".
 *
 * <p>Ids are deduplicated: the rail asks for three statuses in three requests, and a run
 * that changes status between two of them comes back in both.
 */
export function orderLiveRuns(rows: RailRun[]): RailRun[] {
  const byId = new Map<string, RailRun>();
  for (const row of rows) {
    if (!LIVE_STATUSES.includes(row.status)) continue;
    // Later wins: the caller appends the open run last, and the store knows more about it
    // than a list snapshot taken before the last frame arrived.
    byId.set(row.id, row);
  }
  return [...byId.values()].sort((a, b) => {
    const rank = Number(b.status === WAITING) - Number(a.status === WAITING);
    if (rank !== 0) return rank;
    return b.createdAt.localeCompare(a.createdAt);
  });
}

/**
 * How far along, in the mono layer — or the total on its own.
 *
 * <p>`GET /api/runs` carries `stepCount` and no count of the steps that finished, so the
 * rail can only write `3/5 adım` for the run the store is holding in full. Every other row
 * gets the total by itself. A progress figure the server never sent would be a guess, and a
 * guess about how far along someone's work is reads exactly like a fact.
 */
export function progressLabel(stepCount: number, done: number | null): string | null {
  if (stepCount <= 0) return null;
  return done == null ? `${stepCount} adım` : `${done}/${stepCount} adım`;
}

function summaryToRail(row: RunSummary): RailRun {
  return {
    id: row.id,
    goal: row.goal,
    status: row.status,
    stepCount: row.stepCount,
    done: null,
    createdAt: row.createdAt,
  };
}

/** The open run, as a rail row — the one row whose finished-step count is actually known. */
function runToRail(run: Run): RailRun {
  return {
    id: run.id,
    goal: run.goal,
    status: run.status,
    stepCount: run.steps.length,
    done: run.steps.filter((s) => s.status === 'done').length,
    createdAt: run.createdAt,
  };
}

/**
 * Every flow that is still alive, the open one included.
 *
 * <p>WHY THIS EXISTS. Sohbet showed one run and nothing else. On the live box that meant a
 * screen holding 1 of 28 flows stopped on a decision, with the other 27 reachable only by
 * going to Geçmiş and hunting for them. The product promises a team working in parallel and
 * the screen was shaped like a single-file queue (#125, part of #124).
 *
 * <p>WHY IT ASKS THE SERVER PER STATUS. `listRuns({ status })` filters in SQL and answers
 * with the whole set. Pulling the history page and filtering here is the very mistake this
 * rail exists to undo: that page is twenty rows, and only three of the 28 waiting runs fell
 * on it.
 *
 * <p>WHY IT DOES NOT STREAM. There is exactly one SSE connection in this app and it belongs
 * to the open run. A second one per rail row would be 28 sockets to draw 28 lines of text.
 * So it re-reads when the open run changes status — the one moment in the app that says a
 * gate was answered or raised — when the tab comes back, and on a slow timer that ticks only
 * while the tab is visible.
 *
 * <p>It is a hook rather than state inside the rail because the screen has to know whether
 * there is a rail before it lays itself out: no live runs means no column at all, and the
 * conversation keeps the full width.
 */
export function useLiveRuns(current: Run | null): RailRun[] {
  const [rows, setRows] = useState<RailRun[]>([]);
  const inFlight = useRef(false);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const source = getRunSource();
      const answers = await Promise.allSettled(
        LIVE_STATUSES.map((status) => source.listRuns({ status, size: PAGE })),
      );
      // Every request failing is not evidence that the queue emptied — it is evidence that
      // the network is down. Keep the last known rail rather than clearing it.
      if (answers.every((a) => a.status === 'rejected')) return;
      const merged = answers.flatMap((a) => (a.status === 'fulfilled' ? a.value : []));
      // Filtered again in `orderLiveRuns` rather than trusted: a server that does not know
      // the parameter answers with the ordinary page, and the rail would then be listing
      // finished runs as live.
      setRows(merged.map(summaryToRail));
    } finally {
      inFlight.current = false;
    }
  }, []);

  const currentId = current?.id ?? null;
  const currentStatus = current?.status ?? null;

  useEffect(() => {
    void refresh();
  }, [refresh, currentId, currentStatus]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    const timer = setInterval(onVisible, IDLE_REFRESH_MS);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      clearInterval(timer);
    };
  }, [refresh]);

  /*
    The open run is appended last so it wins the deduplication. It has to be here at all
    because a run started a second ago is in no list yet: without it the rail would show
    every flow except the one the user is looking at, and mark none of them current.
  */
  return useMemo(
    () => orderLiveRuns(current ? [...rows, runToRail(current)] : rows),
    [rows, current],
  );
}

type Props = {
  /** Already ordered — see `useLiveRuns`. Never rendered empty; the caller drops the rail. */
  runs: RailRun[];
  currentRunId: string | null;
  onOpen: (runId: string) => void;
};

/**
 * The left rail in Sohbet: one row per live flow, the open one marked.
 *
 * <p>Rows, not cards (DESIGN.md v3). The goal is the sentence; the status is the colour and
 * the word; the step count and the age are machine facts and sit in the mono layer. The
 * accent is spent on one thing only — which run is open.
 */
export function TaskRail({ runs, currentRunId, onOpen }: Props) {
  const reduce = useReducedMotion();
  if (runs.length === 0) return null;

  const waiting = runs.filter((row) => row.status === WAITING);
  const working = runs.filter((row) => row.status !== WAITING);
  // On a phone a rail holding only the run already on screen is the empty rail's twin: it
  // costs a strip of the conversation and says nothing new. CSS drops it at that width; on
  // a desktop the column is already paid for and the row still says nothing else is running.
  const solo = runs.length === 1 && runs[0]?.id === currentRunId;

  let index = 0;
  const group = (title: string, items: RailRun[], key: string) =>
    items.length === 0 ? null : (
      <div className="rail__group" key={key}>
        <h2 className="rail__grouphead t-label">
          {title}
          <span className="rail__count t-mono">{items.length}</span>
        </h2>
        <ul className="rail__list">
          {items.map((row) => (
            <Row
              key={row.id}
              row={row}
              current={row.id === currentRunId}
              index={index++}
              reduce={reduce}
              onOpen={onOpen}
            />
          ))}
        </ul>
      </div>
    );

  return (
    <nav
      className={`rail${solo ? ' rail--solo' : ''}`}
      aria-label="Açık akışlar"
      data-live={runs.length}
    >
      {group('Kararını bekliyor', waiting, 'waiting')}
      {group('Sürüyor', working, 'working')}
    </nav>
  );
}

function Row({
  row,
  current,
  index,
  reduce,
  onOpen,
}: {
  row: RailRun;
  current: boolean;
  index: number;
  reduce: boolean | null;
  onOpen: (runId: string) => void;
}) {
  const status = runStatusMeta(row.status);
  const progress = progressLabel(row.stepCount, row.done);
  return (
    <motion.li className="rail__item" {...enterProps(index, reduce)}>
      <button
        type="button"
        className={`rail__row${current ? ' rail__row--current' : ''}`}
        onClick={() => onOpen(row.id)}
        aria-current={current ? 'true' : undefined}
        title={row.goal}
      >
        <status.Icon size={14} aria-hidden className={`rail__icon ${status.className}`} />
        <span className="rail__body">
          <span className="rail__goal">{row.goal}</span>
          <span className="rail__meta">
            <span className={`rail__status ${status.className}`}>{status.label}</span>
            {progress && <span className="rail__fact t-mono">{progress}</span>}
            <span className="rail__fact t-mono">{formatRelative(row.createdAt)}</span>
          </span>
        </span>
        {current && <span className="sr-only">— şu an açık</span>}
      </button>
    </motion.li>
  );
}
