import { useCallback, useEffect, useRef, useState } from 'react';
import { getPanelSource } from '../data/PanelSource';
import { useRunStore } from '../store/runStore';

/**
 * How many runs are standing at a gate, waiting on a person.
 *
 * <p>WHY THIS DOES NOT USE THE RUN LIST. `GET /api/runs` is paginated and the client asks
 * for its default page — twenty rows. Measured against the live box on 2026-08-01 that
 * page held 3 runs in `awaiting_approval` while the database held 32. A badge built on it
 * would have been wrong by a factor of ten and wrong in the reassuring direction, which is
 * the one thing a badge that exists to stop a silent pile-up must never be.
 *
 * <p>`GET /api/panel` counts by status in SQL and answers with the whole window in one
 * request, so this is a count, not a sample. The window is a year because the server
 * refuses anything longer than 366 days; a run parked for more than a year is not a
 * notification any more.
 */
const WINDOW_DAYS = 364;

/** Slow, and only while the tab is on screen. Not the mechanism — the fallback for one. */
const IDLE_REFRESH_MS = 60_000;

function windowStart(now: number): string {
  return new Date(now - WINDOW_DAYS * 86_400_000).toISOString().slice(0, 10);
}

/**
 * `null` until the first answer arrives, so nothing renders "0 waiting" over a number
 * nobody has counted yet. A failed read leaves the previous count rather than dropping to
 * zero: a request that did not happen is not evidence that the queue emptied.
 *
 * @param key changes whenever the screen changed — a navigation is the cheapest honest
 *            moment to re-count, and it is why this needs no fast timer
 */
export function useAwaitingRuns(key: string): number | null {
  const [count, setCount] = useState<number | null>(null);
  const inFlight = useRef(false);
  // The chat screen decides on gates too. Its run's status is the one signal in the app
  // that a gate was just answered or just raised, and it costs nothing to watch.
  const liveStatus = useRunStore((s) => s.run?.status ?? null);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const report = await getPanelSource().report({ from: windowStart(Date.now()) });
      setCount(report.runs.byStatus.awaiting_approval ?? 0);
    } catch {
      /* keep the last known count; the next trigger will correct it */
    } finally {
      inFlight.current = false;
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh, key, liveStatus]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh();
    }, IDLE_REFRESH_MS);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      clearInterval(timer);
    };
  }, [refresh]);

  return count;
}
