import { motion, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getRunSource } from '../data';
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

/** What one row needs. `done` is null only when the row arrived without the server's count. */
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
 * How far along, in the mono layer — or the total on its own when nobody counted.
 *
 * <p>`n/m adım` is the figure the rail exists for: it is what separates a flow that is
 * nearly finished from one that has not started, and a bare total says neither. The server
 * counts it (`doneStepCount`), so the answer is the same one the run's own screen gives.
 *
 * <p>The fallback is for a row that arrives without the field — an older server, or a
 * cached response served while one is deploying. It writes the total alone rather than
 * `undefined/4` or a `0/4` that claims nothing has run. It is never filled in by counting
 * something else on the client: a browser that decides for itself what "done" means is a
 * second definition of it.
 */
export function progressLabel(stepCount: number, done: number | null): string | null {
  if (stepCount <= 0) return null;
  return done == null ? `${stepCount} adım` : `${done}/${stepCount} adım`;
}

/**
 * The same fact, without its unit, for the row itself.
 *
 * <p>The rail is 260px wide and the row is one line. `adım` is the same four letters
 * printed once per live flow — 28 times on the live box — to say what the shape `3/12`
 * already says under a heading that reads "Sürüyor". The word survives where it is read
 * as prose: the collapsed rail's tooltip and the row's accessible name (#136).
 *
 * <p>An en dash, not a zero, when the server sent no count: `–/12` says nobody counted,
 * `0/12` claims nothing has run.
 */
export function progressFigure(stepCount: number, done: number | null): string | null {
  if (stepCount <= 0) return null;
  return `${done == null ? '–' : done}/${stepCount}`;
}

function summaryToRail(row: RunSummary): RailRun {
  return {
    id: row.id,
    goal: row.goal,
    status: row.status,
    stepCount: row.stepCount,
    // A number or nothing — see `progressLabel`. `?? null` keeps a real zero, which is a
    // measured "none of them yet" and not the same thing as a missing field.
    done: typeof row.doneStepCount === 'number' ? row.doneStepCount : null,
    createdAt: row.createdAt,
  };
}

/**
 * The open run, as a rail row.
 *
 * <p>Its progress comes from the steps the store is holding rather than from the list, so
 * the row moves with the stream instead of waiting for the next refresh — the same count,
 * one source earlier.
 */
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
  /** The sidebar is collapsed: status icon only, and the row's sentence in a tooltip. */
  tight?: boolean;
};

/**
 * One row per live flow, inside the sidebar, with the open one marked.
 *
 * <p>Rows, not cards (DESIGN.md v3). The goal is the sentence; the status is the colour and
 * the word; the step count and the age are machine facts and sit in the mono layer. The
 * accent is spent on one thing only — which run is open.
 *
 * <p>It used to be a column of Sohbet and is now part of the navigation (#130), which is
 * a move rather than a rewrite: the same hook, the same waiting-first order, the same
 * `n/m adım`. What it gained is being on screen from Bugün, Panel and Politikalar too —
 * the flows parked on a decision were previously visible only from the screen you had to
 * already be on to see them.
 */
export function TaskRail({ runs, currentRunId, onOpen, tight = false }: Props) {
  const reduce = useReducedMotion();
  if (runs.length === 0) return null;

  const waiting = runs.filter((row) => row.status === WAITING);
  const working = runs.filter((row) => row.status !== WAITING);

  let index = 0;
  const group = (title: string, items: RailRun[], key: string) =>
    items.length === 0 ? null : (
      /* The waiting group wears the same amber as the nav badge, because it is the
         same set: two numbers in one column that share a colour are read as one
         fact, and two that do not are read as two (#136). */
      <div className={`rail__group${key === 'waiting' ? ' rail__group--waiting' : ''}`} key={key}>
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
              tight={tight}
              onOpen={onOpen}
            />
          ))}
        </ul>
      </div>
    );

  return (
    <nav className="rail" aria-label="Açık akışlar" data-live={runs.length}>
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
  tight,
  onOpen,
}: {
  row: RailRun;
  current: boolean;
  index: number;
  reduce: boolean | null;
  tight: boolean;
  onOpen: (runId: string) => void;
}) {
  const status = runStatusMeta(row.status);
  const progress = progressLabel(row.stepCount, row.done);
  const figure = progressFigure(row.stepCount, row.done);
  /*
    Collapsed, the row is a status icon and nothing else, so the whole sentence moves into
    the tooltip. The browser's own `title` rather than the CSS one the nav items use: this
    list scrolls, and a scrolling box cannot let a tooltip out of its own edges — a native
    tooltip is drawn by the browser and is never clipped by anything.
  */
  const title = tight
    ? `${row.goal} — ${status.label}${progress ? ` · ${progress}` : ''}`
    : row.goal;
  return (
    <motion.li className="rail__item" {...enterProps(index, reduce)}>
      <button
        type="button"
        className={`rail__row${current ? ' rail__row--current' : ''}`}
        onClick={() => onOpen(row.id)}
        aria-current={current ? 'true' : undefined}
        title={title}
      >
        <status.Icon size={14} aria-hidden className={`rail__icon ${status.className}`} />
        {/*
          Two atoms on one line. The row used to carry four — goal, status word, progress
          and age — which is 84 data points across 28 live flows, and one of them said a
          third time what the group head above and the coloured icon beside it already
          said. The age went with it: how long ago a flow started is not a decision made
          in a navigation column (#136).

          The facts that left the row did not leave the product. The status is the icon
          and the group it is in; the whole sentence, the status and the progress are all
          in the accessible name below and in `title`.
        */}
        <span className="rail__goal">{row.goal}</span>
        {figure && (
          <span className="rail__fact t-mono" aria-hidden>
            {figure}
          </span>
        )}
        <span className="sr-only">
          {` — ${status.label}${progress ? `, ${progress}` : ''}${current ? ', şu an açık' : ''}`}
        </span>
      </button>
    </motion.li>
  );
}
