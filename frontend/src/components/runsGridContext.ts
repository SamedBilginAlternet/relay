import { runStatusMeta } from '../lib/status';
import type { RunSummary } from '../types/api';

/**
 * What the cells and the filters need that a row alone does not carry.
 *
 * <p>It goes through ag-grid's `context` rather than through a closure because
 * the grid caches a renderer across data changes: a cell that captured
 * `onOpen` on the first render would keep calling the first render's `onOpen`
 * for the life of the screen. The grid re-reads `context` on every refresh,
 * which is exactly what it is for.
 */
export type RunsGridContext = {
  onOpen: (runId: string) => void;
  /** Goals that appear more than once, so those rows can carry a short id. */
  repeated: Set<string>;
  /** `Karar ver` in the queue, where every row already has the same status. */
  action?: string;
  /** The statuses the table actually holds, in the words the product uses. */
  statuses: { status: string; label: string }[];
};

/**
 * The statuses on offer in the filter, read off the rows.
 *
 * <p>Not off the `RUN_STATUS` map: that has six entries and a page of runs
 * usually holds two. Offering `İptal edildi` when nothing was cancelled is
 * offering a choice whose only possible outcome is an empty table.
 *
 * <p>Not off the grid's own nodes either. `api.forEachNode` answers for the
 * rows the grid has built, and a filter component mounts before that is true —
 * live it produced a select with one option in it, `Tüm durumlar`.
 */
export function statusOptions(rows: RunSummary[]): { status: string; label: string }[] {
  const present = new Set<string>();
  for (const row of rows) present.add(row.status);
  return [...present].map((status) => ({ status, label: runStatusMeta(status).label }));
}
