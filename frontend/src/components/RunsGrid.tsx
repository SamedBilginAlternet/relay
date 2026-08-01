import {
  CellStyleModule,
  ClientSideRowModelModule,
  CustomFilterModule,
  LocaleModule,
  ModuleRegistry,
  RowApiModule,
  RowStyleModule,
  TextFilterModule,
  ValidationModule,
  themeQuartz,
} from 'ag-grid-community';
import type {
  ColDef,
  GridOptions,
  ICellRendererParams,
  ValueFormatterParams,
} from 'ag-grid-community';
import { AgGridReact } from 'ag-grid-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { formatRelative, formatTokens, formatUsd } from '../lib/format';
import { runStatusMeta } from '../lib/status';
import type { RunSummary } from '../types/api';
import { StatusFilter, StatusFloatingFilter } from './StatusFilter';
import { statusOptions, type RunsGridContext } from './runsGridContext';
import '../styles/runs-grid.css';

/*
  Only the parts this table uses.

  `AllCommunityModule` would register the editors, the clipboard, pagination,
  CSV export and the infinite row model — none of which this screen has any
  route to. The grid core is already the single heaviest thing the product
  ships (see the note on the lazy import in HistoryScreen); registering
  features it cannot reach would be paying twice.

  `ValidationModule` is the dev-only bundle that turns an ag-grid misuse into a
  sentence instead of an error code. `import.meta.env.DEV` is statically
  replaced at build time, so the whole branch is dropped from production.
*/
ModuleRegistry.registerModules([
  ClientSideRowModelModule,
  TextFilterModule,
  CustomFilterModule,
  CellStyleModule,
  RowStyleModule,
  LocaleModule,
  // Not decoration: in v33+ the grid API is modular too, and without this
  // `api.getDisplayedRowCount()` is not a method that returns the wrong
  // number — it is not a method at all, and returns `undefined` in silence.
  RowApiModule,
  ...(import.meta.env.DEV ? [ValidationModule] : []),
]);

/**
 * ag-grid, wearing this product's clothes.
 *
 * <p>The grid ships four themes and every one of them is somebody else's design
 * language: shadows for depth, a blue accent, a 40-something row, its own type
 * ramp. This screen had a settled answer to all four already — hairlines
 * instead of shadow, violet only for state, a 38px row, prose in Inter and
 * machine facts in mono at `--machine-size` — and the point of the theming API
 * is that we do not have to re-litigate it in a stylesheet full of `!important`.
 *
 * <p>Every value below is a `var()` reference rather than a copied hex. A theme
 * that inlines `#fafafb` is a second definition of `--bg-subtle`, and the two
 * drift the first time anyone touches the palette. It also means the grid
 * follows a future dark scheme for free, because it is not holding an opinion
 * about colour at all — it is holding a pointer to one.
 *
 * <p>The border and the radius are deliberately `false`/0 here: the frame
 * around the table is `.runs__frame`, which is the same panel the rest of the
 * screen sits in. Two rounded borders one pixel apart is what the old
 * card-in-a-card layout looked like.
 */
const relayTheme = themeQuartz.withParams({
  // Surfaces. Panel for the grid, inset for the header strip — surfaces two
  // and three of the three the product has.
  backgroundColor: 'var(--surface)',
  headerBackgroundColor: 'var(--bg-subtle)',
  oddRowBackgroundColor: 'transparent',
  rowHoverColor: 'var(--bg-subtle)',
  selectedRowBackgroundColor: 'transparent',
  chromeBackgroundColor: 'var(--bg-subtle)',
  menuBackgroundColor: 'var(--surface)',

  // Type. `inherit` rather than a stack of our own: the page already sets
  // Inter, and naming it twice is how a heading ends up in a different font
  // from the paragraph under it.
  fontFamily: 'inherit',
  fontSize: '15px',
  textColor: 'var(--fg)',
  cellTextColor: 'var(--fg)',
  foregroundColor: 'var(--fg)',
  subtleTextColor: 'var(--fg-faint)',
  headerFontFamily: 'inherit',
  headerFontSize: '12px',
  headerFontWeight: 600,
  headerTextColor: 'var(--fg-muted)',
  menuTextColor: 'var(--fg)',

  // Hairlines, never shadow. `--line` and not `--line-soft`: at 6% alpha forty
  // rows read as one continuous grey field, which is the thing the list was
  // fixed for in #68.
  borderColor: 'var(--line)',
  rowBorder: { style: 'solid', width: 1, color: 'var(--line)' },
  headerRowBorder: { style: 'solid', width: 1, color: 'var(--line)' },
  columnBorder: false,
  headerColumnBorder: false,
  wrapperBorder: false,
  wrapperBorderRadius: 0,
  borderRadius: 'var(--r-btn)',
  menuBorder: { style: 'solid', width: 1, color: 'var(--line)' },
  // The one place a shadow is allowed: a popup genuinely floats above the page
  // and has nothing behind it to draw a step against.
  menuShadow: 'var(--shadow)',
  popupShadow: 'var(--shadow)',

  // Metrics. 38px is 15px of goal at 1.35 plus 6px above and below — the same
  // arithmetic the hand-rolled row used, kept so the page does not resize.
  rowHeight: 38,
  headerHeight: 28,
  cellHorizontalPadding: 'var(--s-12)',
  iconSize: 14,
  iconColor: 'var(--fg-faint)',
  // A table of three runs is three rows tall. The grid's default reserves
  // ~150px of empty panel under the last one so that an empty grid does not
  // collapse — this screen has an empty state that says so in words, and does
  // not need a blank rectangle saying it in whitespace.
  autoHeightMinBodyHeight: 0,
  // The columns are resizable and the cursor still says so; what goes is the
  // permanent vertical tick the handle paints between every pair of heads.
  // The old strip had no rules between its columns and neither does this one.
  headerColumnResizeHandleColor: 'transparent',

  // Violet means "waiting on you" everywhere else in the product, so the grid
  // does not get to spend it on a hover or a selection. It keeps it for the
  // one thing that is genuinely a choice the reader made: an active filter.
  accentColor: 'var(--accent)',
  focusShadow: { spread: 2, color: 'var(--accent-line)' },
});

type Props = {
  rows: RunSummary[];
  /** Goals that appear more than once, so the row can carry its short id. */
  repeated: Set<string>;
  /** `Karar ver` in the queue, where every row already has the same status. */
  action?: string;
  /** Column heading over the last column — `Durum` in the log, `Karar` in the queue. */
  lastHeader: string;
  onOpen: (runId: string) => void;
  /** Marks the queue, whose glyphs are all the same state and take the accent. */
  waiting?: boolean;
  /** Live-region text when a filter has hidden rows; owned by the screen. */
  onFilterChange?: (shown: number, total: number) => void;
};

function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 6);
}

/**
 * The accessible name of one run, spelled out on the control that opens it.
 *
 * <p>The columns print bare figures — `3`, `1.200` — because the units are in
 * the head, printed once. Read out loud a bare `3` is not a step count, so the
 * label carries the units the row does not, and it carries all five facts
 * including the two that the narrow layout hides.
 */
export function runLabel(row: RunSummary, action?: string): string {
  const status = runStatusMeta(row.status);
  return [
    `${row.goal} — ${action ?? status.label}`,
    formatRelative(row.createdAt),
    `${row.stepCount} adım`,
    `${formatTokens(row.costTokens)} token`,
    formatUsd(row.costUsd),
  ].join(', ');
}

/*
  Numbers are never formatted here.

  `formatRelative`, `formatTokens` and `formatUsd` decide what a time, a token
  count and a price look like for the whole product. A `valueFormatter` that
  did its own `toFixed` would be a second answer to the same question, and the
  four-decimal price that read `$0.0001` for a step the API reports as
  `0.000113` is exactly what that costs.
*/
const relative = (p: ValueFormatterParams<RunSummary, string>) => formatRelative(p.value ?? null);
const tokens = (p: ValueFormatterParams<RunSummary, number>) => formatTokens(p.value ?? 0);
const usd = (p: ValueFormatterParams<RunSummary, number>) => formatUsd(p.value ?? Number.NaN);

/**
 * The left gutter: the one column the eye walks to find a failure in a page of
 * finished runs. Same 16px on every row whatever the row says on the right,
 * and colour is never the whole signal — the glyph differs per status and the
 * button next to it spells the status out.
 */
function MarkCell({ data }: ICellRendererParams<RunSummary>) {
  if (!data) return null;
  const status = runStatusMeta(data.status);
  const Icon = status.Icon;
  return (
    <span className={`run-cell__mark ${status.className}`} aria-hidden>
      <Icon size={15} />
    </span>
  );
}

/**
 * The goal, and the row's only tab stop.
 *
 * <p>An ag-grid row is a `div` with `role="row"`; there is no element in it a
 * keyboard user can press and no name a screen reader can read out. The button
 * that used to *be* the row moves inside this cell instead, keeping both — and
 * keeping the id, which is the only thing telling two runs of the same prompt
 * apart and therefore never leaves the row that needs it.
 */
function GoalCell({ data, context }: ICellRendererParams<RunSummary>) {
  if (!data) return null;
  const ctx = context as RunsGridContext;
  return (
    <button
      type="button"
      className="run-cell__open"
      aria-label={runLabel(data, ctx.action)}
      onClick={(event) => {
        // The row is clickable too; without this the row handler fires as well
        // and the detail screen is pushed twice.
        event.stopPropagation();
        ctx.onOpen(data.id);
      }}
    >
      <span className="run-cell__text">{data.goal}</span>
      {ctx.repeated.has(data.goal) && (
        <code className="run-cell__id t-mono">#{shortId(data.id)}</code>
      )}
    </button>
  );
}

/**
 * The last column: the status, or — in the queue, where every row carries the
 * same one — what the reader is being asked to do instead.
 */
function EndCell({ data, context }: ICellRendererParams<RunSummary>) {
  if (!data) return null;
  const ctx = context as RunsGridContext;
  const status = runStatusMeta(data.status);
  return (
    <span className={`run-cell__end${ctx.action ? ' run-cell__end--act' : ` ${status.className}`}`}>
      {ctx.action ?? status.label}
    </span>
  );
}

/**
 * Which columns fit.
 *
 * <p>The hand-rolled grid did this in a media query. ag-grid owns its column
 * widths, so the same decision has to be made in JavaScript — but it is the
 * same decision, at the same two widths, for the same reason: at 1024px this
 * page gets 716px, and seven fixed columns would leave the goal about
 * twenty-six characters of Turkish.
 *
 * <p>Adım and Token go first because they are the two facts nobody navigates
 * by. Every row's accessible name still reads all five, so nothing is lost —
 * only unprinted.
 */
function useWidth(): 'wide' | 'medium' | 'narrow' {
  const [width, setWidth] = useState<'wide' | 'medium' | 'narrow'>(() => measure());
  useEffect(() => {
    const onResize = () => setWidth(measure());
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  return width;
}

function measure(): 'wide' | 'medium' | 'narrow' {
  if (typeof window === 'undefined') return 'wide';
  if (window.innerWidth <= 860) return 'narrow';
  if (window.innerWidth <= 1180) return 'medium';
  return 'wide';
}

export function RunsGrid({
  rows,
  repeated,
  action,
  lastHeader,
  onOpen,
  waiting,
  onFilterChange,
}: Props) {
  const width = useWidth();
  const gridRef = useRef<AgGridReact<RunSummary>>(null);

  const context = useMemo<RunsGridContext>(
    () => ({ onOpen, repeated, action, statuses: statusOptions(rows) }),
    [onOpen, repeated, action, rows],
  );

  const columnDefs = useMemo<ColDef<RunSummary>[]>(() => {
    const numeric: Partial<ColDef<RunSummary>> = {
      cellClass: 'run-cell__num t-mono',
      headerClass: 'run-head--num',
      sortable: true,
      /*
        No filter on the three figures, deliberately. A number filter here
        would be four more clicks to express "runs over a dollar" — a question
        one click on the header already answers better, because sorting shows
        you the distribution instead of making you guess a threshold. Adım and
        Token are, by this screen's own layout rules, the two facts nobody
        navigates by; giving them a filter menu would be furniture.
      */
      filter: false,
    };
    return [
      {
        colId: 'mark',
        headerName: '',
        width: 40,
        minWidth: 40,
        maxWidth: 40,
        sortable: false,
        filter: false,
        resizable: false,
        cellClass: 'run-cell--mark',
        cellRenderer: MarkCell,
        // The glyph is the status the last column already prints in words. It
        // is a scanning aid, not a second fact, so it is not separately
        // sortable and not separately filterable.
        suppressHeaderMenuButton: true,
      },
      {
        colId: 'goal',
        field: 'goal',
        headerName: 'İş',
        flex: 1,
        minWidth: 200,
        sortable: true,
        /*
          The one filter this screen was actually missing. A hundred runs of a
          handful of recurring prompts is what the live box looks like, and
          "the KAN one" is how people ask for a run. Contains-only: `equals`
          on a free-text Turkish goal is a filter nobody will ever satisfy by
          typing, and the option dropdown that offers it is four choices of
          which three are traps.
        */
        filter: 'agTextColumnFilter',
        filterParams: {
          filterOptions: ['contains'],
          maxNumConditions: 1,
          debounceMs: 200,
          filterPlaceholder: 'İşin adında ara',
        },
        floatingFilter: true,
        floatingFilterComponentParams: { filterPlaceholder: 'İşin adında ara' },
        // The funnel in the header opens the same one-line contains box that is
        // already sitting in the row below it. Two doors into one control is
        // one door too many, and the header one is a glyph on a strip that had
        // nothing but words on it before.
        suppressHeaderMenuButton: true,
        suppressHeaderFilterButton: true,
        suppressFloatingFilterButton: true,
        cellRenderer: GoalCell,
        cellClass: 'run-cell--goal',
      },
      {
        colId: 'createdAt',
        field: 'createdAt',
        headerName: 'Zaman',
        width: 116,
        valueFormatter: relative,
        ...numeric,
        /*
          No date filter. The column does not print a date — `formatRelative`
          writes `4 sa önce` for anything under a week — so a date picker over
          it asks the reader to filter on a value that is nowhere on screen.
          The list is one page, newest first; the reader who wants a window in
          time sorts, and sorting is on.
        */
      },
      {
        colId: 'stepCount',
        field: 'stepCount',
        headerName: 'Adım',
        width: 68,
        hide: width === 'medium' || width === 'narrow',
        ...numeric,
      },
      {
        colId: 'costTokens',
        field: 'costTokens',
        headerName: 'Token',
        width: 96,
        valueFormatter: tokens,
        hide: width === 'medium' || width === 'narrow',
        ...numeric,
      },
      {
        colId: 'costUsd',
        field: 'costUsd',
        headerName: 'Tutar',
        width: 108,
        valueFormatter: usd,
        hide: width === 'narrow',
        ...numeric,
      },
      {
        colId: 'status',
        field: 'status',
        headerName: lastHeader,
        width: 136,
        sortable: true,
        /*
          The second filter, and a custom one rather than a text box: the
          reader thinks in `Hata` and `Onay bekliyor`, and the underlying value
          is `failed` and `awaiting_approval`. A text filter over this column
          matches neither what is printed nor what anyone would type. The
          custom component lists the statuses actually present, in the words
          the rest of the product uses.

          Community has no Set Filter — that is an enterprise part — so this is
          a native `<select>`, which is also the cheapest thing to make
          keyboard- and screen-reader-complete.
        */
        filter: StatusFilter,
        floatingFilter: true,
        floatingFilterComponent: StatusFloatingFilter,
        suppressHeaderMenuButton: true,
        suppressHeaderFilterButton: true,
        suppressFloatingFilterButton: true,
        cellRenderer: EndCell,
        cellClass: 'run-cell--end',
        headerClass: 'run-head--num',
      },
    ];
  }, [lastHeader, width]);

  /*
    Reported on `modelUpdated` and not only on `filterChanged`, because a
    filter that is still typed in when the list underneath is refetched hides a
    different number of rows than it did a second ago. `filterChanged` alone
    left "40 kayıttan 12" standing over a list of nine.
  */
  const onModelUpdated = useCallback(() => {
    const api = gridRef.current?.api;
    if (!api || !onFilterChange) return;
    onFilterChange(api.getDisplayedRowCount(), rows.length);
  }, [onFilterChange, rows.length]);

  const options = useMemo<GridOptions<RunSummary>>(
    () => ({
      /*
        The page scrolls; the table does not. A grid with its own scrollbar
        inside a page with another one is two scrollbars arguing, and it also
        means the reader cannot see where the list ends. `autoHeight` also
        renders every row rather than a viewport's worth, which is what keeps
        the rows findable by a screen reader and by a test.
      */
      domLayout: 'autoHeight',
      // One row of controls under the column heads, at the height of a form
      // field rather than of a row of data.
      floatingFiltersHeight: 36,
      // DOM order = visual order, so a screen reader reads the row the way it
      // is drawn and Tab goes where the eye went.
      ensureDomOrder: true,
      suppressColumnVirtualisation: true,
      suppressCellFocus: false,
      // Nothing on this row is editable and nothing is a range: the grid's
      // selection would be a violet band that means nothing.
      rowSelection: undefined,
      suppressMovableColumns: true,
      // Right-click is the browser's on this screen; there is nothing in the
      // grid's own menu (copy, export) that this product offers elsewhere.
      preventDefaultOnContextMenu: false,
      getRowId: (p) => p.data.id,
      // A row is a link to the run, wherever on it you press.
      onRowClicked: (event) => {
        if (event.data) onOpen(event.data.id);
      },
      // Enter on a focused cell does what Enter on the goal button does. Without
      // it the keyboard can reach every cell of a run and open none of them.
      onCellKeyDown: (event) => {
        const key = (event.event as KeyboardEvent | null)?.key;
        if (key === 'Enter' && event.data) onOpen(event.data.id);
      },
    }),
    [onOpen],
  );

  /*
    Rows are re-read rather than re-mounted when the tab changes: `getRowId`
    keys them by run id, so the grid keeps whatever filter is typed into the
    goal box across a refresh of the same list.
  */
  return (
    <div className="runs-grid" data-waiting={waiting ? 'true' : undefined}>
      <AgGridReact<RunSummary>
        ref={gridRef}
        theme={relayTheme}
        rowData={rows}
        columnDefs={columnDefs}
        context={context}
        onModelUpdated={onModelUpdated}
        // Turkish, because the rest of the screen is. The grid's own strings
        // are English and there is no reason for a filter menu to be the one
        // English thing on the page.
        localeText={LOCALE}
        {...options}
      />
    </div>
  );
}

/**
 * The handful of grid strings this table can actually surface, in Turkish.
 *
 * <p>Only the ones reachable from the parts registered above — there is no
 * value in translating the pivot panel of a grid that has no pivot panel.
 */
const LOCALE: Record<string, string> = {
  contains: 'içerir',
  filterOoo: 'Filtrele...',
  applyFilter: 'Uygula',
  resetFilter: 'Temizle',
  clearFilter: 'Temizle',
  cancelFilter: 'Vazgeç',
  noRowsToShow: 'Kayıt yok',
  blank: 'Boş',
  ariaFilterInput: 'Filtre metni',
  ariaFilterMenuOpen: 'Filtreyi aç',
  ariaSearchFilterValues: 'Filtre değerleri',
  ariaColumn: 'Sütun',
  ariaRowSelect: 'Satır',
  ariaSortableColumn: 'Sıralanabilir sütun',
  ariaFilterColumn: 'Filtrelenebilir sütun',
};
