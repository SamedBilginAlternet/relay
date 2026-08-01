import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { formatRelative, formatTokens, formatUsd } from '../lib/format';
import { runStatusMeta } from '../lib/status';
import type { RunSummary } from '../types/api';
import '../styles/runs-cards.css';

/**
 * Akışlar, as a list of cards.
 *
 * <p>WHY CARDS, WHEN THIS SCREEN SPENT THREE ISSUES BECOMING A TABLE (#163).
 * Because the owner asked for them, after being shown #68/#124/#127 and the
 * reasoning that produced the strip. The complaint the decision answers is that the log
 * read as a band floating in a page rather than as the thing the screen is
 * about: at 1440x900 the table was 181px of a 900px viewport with 176px of
 * heading above it, and every run was a 38px line — below the 44px this
 * product calls a touch target everywhere else.
 *
 * <p>WHAT THE TABLE WAS RIGHT ABOUT, KEPT. #68's complaint was never the shape
 * of the frame, it was that `4.246 token` and `614 token` ended in different
 * places on consecutive rows, so nothing lined up down the page. Every card
 * here is the same grid: the state mark on the same x, the goal from the same
 * x, and the four figures in fixed tracks with the value right-aligned against
 * its unit — so Zaman, Adım, Token and Tutar sit in the same place on card one
 * and on card fifteen, and the digits still stack. A card whose contents flow
 * would put that back the way it was, and it is the one thing this rewrite is
 * not allowed to cost.
 *
 * <p>WHAT THE GRID COST AND WHY IT IS GONE. ag-grid arrived in #156 to buy
 * per-column filtering, at 232KB gzipped in a lazy chunk. A card has no
 * columns: its header row, its column widths, its resize handles and its
 * per-column filter menus are all furniture over a list of blocks. Keeping it
 * would be paying 232KB to slice an array. The two filters and the sort it
 * bought stay — they are the controls above the list — and they are ~120 lines
 * of this file.
 */

type Props = {
  rows: RunSummary[];
  /** Goals that appear more than once, so the card can carry a short id. */
  repeated: Set<string>;
  /** `Karar ver` in the queue, where every card already has the same status. */
  action?: string;
  onOpen: (runId: string) => void;
  /** Marks the queue, whose marks are all the same state and take the accent. */
  waiting?: boolean;
  /** Live-region text when a filter has hidden cards; owned by the screen. */
  onFilterChange?: (shown: number, total: number) => void;
};

/**
 * How many runs one page holds.
 *
 * <p>Derived, and the arithmetic is from the screen rather than from a habit.
 * Measured at 1440x900: a card is 72.7px — 12px of padding, a 20.3px goal
 * line, 6px, a 20.4px figure line, 12px, and two hairlines — and the gap
 * between two cards is 8px, so the list costs 80.7px a run. The list starts
 * 231.9px down the page, under the heading, the tabs, the note and the filter
 * row, which leaves 668px of it visible: eight cards and a bit. Fifteen cards
 * is 1,202px, or 1.8 screens of list — about the distance a reader will scroll
 * before reaching for a control instead of the scrollbar, and short enough
 * that the pager is one flick away rather than a page and a half.
 *
 * <p>Not 20, which is what a table library defaults to: 20 is 1,614px, two and
 * a half screens, and the pager stops being something you know is there. It
 * also divides the live box's 222 runs into 15 pages rather than 12, which is
 * the only thing 20 would have bought.
 */
export const PAGE_SIZE = 15;

/** The six-character tail a repeated goal is told apart by. */
function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 6);
}

/**
 * The accessible name of one run, on the control that opens it.
 *
 * <p>The figures are printed with their units next to them now, which the
 * table's bare `3` could not do — but the label still spells all five facts
 * out in one string, because a card read aloud is otherwise five fragments
 * with no sentence holding them together.
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

/**
 * The statuses on offer in the filter, read off the runs in hand.
 *
 * <p>Not off `RUN_STATUS`: that map has six entries and a log usually holds
 * two. Offering `İptal edildi` when nothing was cancelled is offering a choice
 * whose only possible outcome is an empty list.
 */
export function statusOptions(rows: RunSummary[]): { status: string; label: string }[] {
  const present = new Set<string>();
  for (const row of rows) present.add(row.status);
  return [...present].map((status) => ({ status, label: runStatusMeta(status).label }));
}

/**
 * Case-folded the Turkish way, on purpose.
 *
 * <p>`'İŞ'.toLowerCase()` is `i̇ş` in JavaScript — an `i` with a combining dot
 * — and `'I'.toLowerCase()` is `i`, not `ı`. Either one is enough to make
 * "IŞIK" not match "ışık", which on a screen whose goals are Turkish
 * sentences is the filter failing at the first word anybody would type.
 */
function fold(text: string): string {
  return text.toLocaleLowerCase('tr');
}

export type SortKey = 'yeni' | 'eski' | 'pahali' | 'adim' | 'token';

/**
 * The orders the column heads used to offer, in one control.
 *
 * <p>The table sorted by clicking Zaman, Adım, Token or Tutar. Cards have no
 * heads to click, so the same four questions live here — and `En eski önce` is
 * added because the reversal of a sorted column was a second click on it and
 * is otherwise unreachable.
 */
export const SORTS: { key: SortKey; label: string }[] = [
  { key: 'yeni', label: 'En yeni önce' },
  { key: 'eski', label: 'En eski önce' },
  { key: 'pahali', label: 'En pahalı önce' },
  { key: 'adim', label: 'En çok adım' },
  { key: 'token', label: 'En çok token' },
];

/** Everything the reader has narrowed the list down to. */
export type RunQuery = { goal: string; status: string | null; sort: SortKey };

/**
 * The list as the reader has asked for it: filtered, then ordered.
 *
 * <p>Pure and exported so the claims about it can be tested without a DOM: a
 * filter that quietly drops a row is the failure mode this screen has already
 * been bitten by, and it is cheaper to assert here than through a click.
 */
export function applyQuery(rows: RunSummary[], query: RunQuery): RunSummary[] {
  const needle = fold(query.goal.trim());
  const kept = rows.filter(
    (row) =>
      (needle === '' || fold(row.goal).includes(needle)) &&
      (query.status == null || row.status === query.status),
  );
  const by = {
    // `localeCompare` on the ISO string, not `Date.parse`: the server sends the
    // same offset for every row, so the text order is the time order and an
    // unparseable date sorts predictably instead of becoming NaN.
    yeni: (a: RunSummary, b: RunSummary) => b.createdAt.localeCompare(a.createdAt),
    eski: (a: RunSummary, b: RunSummary) => a.createdAt.localeCompare(b.createdAt),
    pahali: (a: RunSummary, b: RunSummary) => (b.costUsd ?? 0) - (a.costUsd ?? 0),
    adim: (a: RunSummary, b: RunSummary) => b.stepCount - a.stepCount,
    token: (a: RunSummary, b: RunSummary) => b.costTokens - a.costTokens,
  }[query.sort];
  return [...kept].sort(by);
}

/** One figure: the value in mono against a fixed track, its unit beside it. */
function Fig({ value, unit }: { value: string; unit?: string }) {
  return (
    <span className="run-card__fig">
      <b className="run-card__value t-mono">{value}</b>
      {unit && <span className="run-card__unit">{unit}</span>}
    </span>
  );
}

/**
 * One run.
 *
 * <p>The whole card is the control. A card with a button inside it and a click
 * handler around it is two answers to one gesture, and a nested second button
 * (`Karar ver` as a control of its own) would be a second tab stop leading to
 * the same screen — so `Karar ver` is what the state says, in the state's own
 * colour, on a card that opens where the word points.
 */
function RunCard({
  row,
  repeated,
  action,
  onOpen,
}: {
  row: RunSummary;
  repeated: boolean;
  action?: string;
  onOpen: (runId: string) => void;
}) {
  const status = runStatusMeta(row.status);
  const Icon = status.Icon;
  return (
    <li className="run-card">
      <button
        type="button"
        className="run-card__open"
        aria-label={runLabel(row, action)}
        onClick={() => onOpen(row.id)}
      >
        {/* Colour is never the whole signal: the glyph differs per status and
            the word next to it says the same thing in Turkish. */}
        <span className={`run-card__mark ${status.className}`} aria-hidden>
          <Icon size={16} />
        </span>
        <span className="run-card__goal">
          <span className="run-card__text">{row.goal}</span>
          {/* Bare, never `#7fd92e`: the hash read as an issue number, which on a
              screen full of Jira keys is the one thing it must not look like. */}
          {repeated && <code className="run-card__id t-mono">{shortId(row.id)}</code>}
        </span>
        <span className={`run-card__state${action ? ' run-card__state--act' : ` ${status.className}`}`}>
          {action ?? status.label}
        </span>
        <span className="run-card__figs">
          <Fig value={formatRelative(row.createdAt)} />
          <Fig value={String(row.stepCount)} unit="adım" />
          <Fig value={formatTokens(row.costTokens)} unit="token" />
          {/* `formatUsd` writes `—` for a cost nobody measured. A card that
              printed $0.000000 there would be inventing a measurement. */}
          <Fig value={formatUsd(row.costUsd ?? Number.NaN)} />
        </span>
      </button>
    </li>
  );
}

export function RunCards({ rows, repeated, action, onOpen, waiting, onFilterChange }: Props) {
  const [goal, setGoal] = useState('');
  const [status, setStatus] = useState<string | null>(null);
  const [sort, setSort] = useState<SortKey>('yeni');
  const [page, setPage] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);

  const statuses = useMemo(() => statusOptions(rows), [rows]);
  const view = useMemo(() => applyQuery(rows, { goal, status, sort }), [rows, goal, status, sort]);

  /*
    Reported on every change of the list underneath, not only when a control is
    touched. A filter still typed in when the screen refetches hides a different
    number of runs than it did a second ago, and the note above the list said
    "40 kayıttan 12" over a list of nine until it was made to re-report here.
  */
  useEffect(() => {
    onFilterChange?.(view.length, rows.length);
  }, [onFilterChange, view.length, rows.length]);

  /*
    The page is a position in a list, so it cannot outlive the list it is a
    position in: typing into the filter on page 9 of 15 leaves nine pages of
    nothing, and the reader concludes their runs are gone.
  */
  const pageCount = Math.max(1, Math.ceil(view.length / PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);
  useEffect(() => setPage(0), [goal, status, sort]);

  const first = current * PAGE_SIZE;
  const shown = view.slice(first, first + PAGE_SIZE);

  const go = useCallback((next: number) => {
    setPage(next);
    // Back to the top of the list, not the top of the page: the controls that
    // moved you stay where your hand is. `scrollIntoView` is missing in jsdom
    // and on very old Safari, and a pager that throws is worse than one that
    // leaves the scroll alone. No smooth behaviour, so there is nothing for
    // `prefers-reduced-motion` to suppress.
    listRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, []);

  return (
    <div className="runs-cards" data-waiting={waiting ? 'true' : undefined}>
      {/*
        The two filters and the sort, above the list rather than inside a
        header strip. They were in a permanent row under the column heads for
        the reason they are permanent here: below 1024px this screen is read on
        a tablet, and a control behind a hover is a control that does not exist.
      */}
      <div className="runs-tools">
        <input
          type="search"
          className="runs-tools__field"
          placeholder="İşin adında ara"
          aria-label="İşin adında ara"
          value={goal}
          onChange={(event) => setGoal(event.target.value)}
        />
        <select
          className="runs-tools__field runs-tools__select"
          aria-label="Duruma göre filtrele"
          value={status ?? ''}
          onChange={(event) => setStatus(event.target.value === '' ? null : event.target.value)}
        >
          <option value="">Tüm durumlar</option>
          {statuses.map((option) => (
            <option key={option.status} value={option.status}>
              {option.label}
            </option>
          ))}
        </select>
        <select
          className="runs-tools__field runs-tools__select"
          aria-label="Sıralama"
          value={sort}
          onChange={(event) => setSort(event.target.value as SortKey)}
        >
          {SORTS.map((option) => (
            <option key={option.key} value={option.key}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {shown.length === 0 ? (
        /* Neutral: a filter that matches nothing is the reader's question
           answered, not an error. The count above says what is hidden. */
        <p className="runs-cards__none">Filtreye uyan akış yok.</p>
      ) : (
        <ul className="runs-cards__list" ref={listRef}>
          {shown.map((row) => (
            <RunCard
              key={row.id}
              row={row}
              repeated={repeated.has(row.goal)}
              action={action}
              onOpen={onOpen}
            />
          ))}
        </ul>
      )}

      {view.length > PAGE_SIZE && (
        <Pager
          first={first + 1}
          last={first + shown.length}
          total={view.length}
          page={current}
          pageCount={pageCount}
          onGo={go}
        />
      )}
    </div>
  );
}

/**
 * The pager.
 *
 * <p>Every figure on it is a figure about the list actually in hand: the
 * screen walks the server's pages until one comes back short, so `222` is the
 * number of runs recorded, not the number that fitted in one response. A pager
 * whose total is a page size is the bug the note line above the list was
 * written to fix (#124), and putting it back inside the pager would be the
 * same lie in a smaller font.
 *
 * <p>Four buttons and a page number, all Turkish, all at least 44px. `İlk` and
 * `Son` earn their place at fifteen pages: without them the only way to the
 * oldest run is fourteen presses of `Sonraki`.
 */
function Pager({
  first,
  last,
  total,
  page,
  pageCount,
  onGo,
}: {
  first: number;
  last: number;
  total: number;
  page: number;
  pageCount: number;
  onGo: (page: number) => void;
}) {
  const atStart = page === 0;
  const atEnd = page >= pageCount - 1;
  return (
    <nav className="pager" aria-label="Sayfalama">
      {/* Announced, because pressing `Sonraki` changes fifteen rows of a list
          a screen reader is not looking at. */}
      <p className="pager__summary t-mono" aria-live="polite">
        {formatTokens(first)}–{formatTokens(last)} / {formatTokens(total)}
      </p>
      <div className="pager__controls">
        <button
          type="button"
          className="pager__btn"
          aria-label="İlk sayfa"
          disabled={atStart}
          onClick={() => onGo(0)}
        >
          «
        </button>
        <button
          type="button"
          className="pager__btn"
          aria-label="Önceki sayfa"
          disabled={atStart}
          onClick={() => onGo(page - 1)}
        >
          ‹
        </button>
        <p className="pager__page">
          Sayfa <b className="t-mono">{formatTokens(page + 1)}</b> /{' '}
          <b className="t-mono">{formatTokens(pageCount)}</b>
        </p>
        <button
          type="button"
          className="pager__btn"
          aria-label="Sonraki sayfa"
          disabled={atEnd}
          onClick={() => onGo(page + 1)}
        >
          ›
        </button>
        <button
          type="button"
          className="pager__btn"
          aria-label="Son sayfa"
          disabled={atEnd}
          onClick={() => onGo(pageCount - 1)}
        >
          »
        </button>
      </div>
    </nav>
  );
}
