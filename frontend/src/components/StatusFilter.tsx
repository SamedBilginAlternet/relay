import type { CustomFilterProps, CustomFloatingFilterProps } from 'ag-grid-react';
import { useGridFilter, useGridFloatingFilter } from 'ag-grid-react';
import { useCallback } from 'react';
import type { RunSummary } from '../types/api';
import type { RunsGridContext } from './runsGridContext';

/**
 * "Only the failures", in one control.
 *
 * <p>WHY IT IS NOT A TEXT FILTER. The column prints `Hata` and stores `failed`.
 * A text box over it matches the stored value, so the reader types the word
 * they can see and gets nothing — the filter that is worse than no filter,
 * because it answers a question wrongly instead of not answering it. The list
 * here is built from the statuses actually in the table and labelled through
 * `runStatusMeta`, the same map the rows and the sidebar badge read, so it can
 * never offer a word the product does not use.
 *
 * <p>WHY IT IS A NATIVE `select`. ag-grid's Set Filter — the checkbox list
 * everyone pictures — is an enterprise part and is not in this build. A
 * `<select>` is keyboard-, screen-reader- and touch-complete without a line of
 * code, and over a set that is six long at the absolute most, a checkbox list
 * buys nothing but a scrollbar.
 *
 * <p>The model is the raw status string, or `null` for "any". `null` is what
 * tells the grid the filter is off, and therefore what clears the funnel from
 * the header — a filter that reports itself active while matching everything
 * is a claim the reader has to click twice to disprove.
 */

type StatusOption = { status: string; label: string };

function StatusSelect({
  id,
  model,
  options,
  onChange,
  className,
}: {
  id: string;
  model: string | null;
  options: StatusOption[];
  onChange: (next: string | null) => void;
  className: string;
}) {
  return (
    <select
      id={id}
      className={className}
      // Named out loud: in the header row this sits under the word `Durum`,
      // which a screen reader reading the control alone never gets to.
      aria-label="Duruma göre filtrele"
      value={model ?? ''}
      onChange={(event) => onChange(event.target.value === '' ? null : event.target.value)}
    >
      <option value="">Tüm durumlar</option>
      {options.map((option) => (
        <option key={option.status} value={option.status}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

/**
 * The filter itself: it owns the model and answers the only question the grid
 * asks a filter, which is whether a given row survives it.
 */
export function StatusFilter({
  model,
  onModelChange,
  context,
}: CustomFilterProps<RunSummary, RunsGridContext, string>) {
  const doesFilterPass = useCallback(
    ({ data }: { data: RunSummary }) => model == null || data.status === model,
    [model],
  );

  useGridFilter({ doesFilterPass });

  return (
    <div className="runs-filter">
      <StatusSelect
        id="runs-status-filter"
        className="runs-filter__select"
        model={model}
        options={context.statuses}
        onChange={onModelChange}
      />
    </div>
  );
}

/**
 * The same control, in the row under the column heads.
 *
 * <p>A filter behind a funnel icon that only appears on hover is not
 * discoverable and, below 1024px where this screen is read on a tablet, not
 * reachable at all — there is no hover on a touch screen. The filter row costs
 * one header's height and makes both filters things the reader can see are
 * there, which is the difference between a feature and a feature nobody finds.
 */
export function StatusFloatingFilter({
  model,
  onModelChange,
  context,
}: CustomFloatingFilterProps<unknown, RunSummary, RunsGridContext, string>) {
  useGridFloatingFilter({});
  return (
    <StatusSelect
      id="runs-status-floating-filter"
      className="runs-filter__select runs-filter__select--inline"
      model={model}
      options={context.statuses}
      onChange={onModelChange}
    />
  );
}
