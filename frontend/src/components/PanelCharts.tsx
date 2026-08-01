/**
 * The four pictures the panel draws, and nothing else.
 *
 * <p>NO CHART LIBRARY, ON PURPOSE. A pie is one circle with a dash array, a funnel is
 * stacked rectangles, a bar is a rectangle with a percentage width. Recharts is ~95KB
 * gzip on top of a ~150KB bundle for that, and it arrives with its own colour scale,
 * its own type ramp and its own tooltip — three things this product already has
 * opinions about. Hand-drawn, every mark inherits the design tokens for free.
 *
 * <p>COLOUR IS NEVER THE SIGNAL. Every slice, stage and bar prints its own number
 * beside it, and a legend row names it in words. The drawing is the fast path for
 * someone who already knows the shape; the text is the record. Nothing on these charts
 * can only be learned by telling two colours apart.
 *
 * <p>The palette is declared in panel.css (`--chart-*`) and derived from the shared
 * tokens. Violet is not in it: on this product violet means state, and a chart series
 * is not a state.
 */

import type { CSSProperties } from 'react';
import type { FunnelSlice, FunnelStage } from '../lib/funnel';

/** `animation-delay` as a custom property, so reduced motion can zero it out in CSS. */
function delay(index: number): CSSProperties {
  return { '--panel-delay': `${Math.min(index, 8) * 40}ms` } as CSSProperties;
}

function percent(ratio: number): string {
  if (!Number.isFinite(ratio)) return '—';
  return `%${Math.round(ratio * 1000) / 10}`;
}

// ---------------------------------------------------------------------------
// Pie

export type Slice = { key: string; label: string; value: number; color: string };

const R = 52;
const C = 2 * Math.PI * R;
/** Hairline between neighbouring arcs, in path units. */
const GAP = 1.6;

/**
 * Six parts of one whole — which is exactly what `runs.byStatus` is, and the reason it
 * gets a pie rather than the sixth copy of the same bar chart.
 *
 * <p>A ring rather than a filled disc: the hole carries the total, so the one number the
 * slices are shares *of* is inside the picture instead of in a caption underneath it.
 *
 * <p>Zeros are not drawn and are not listed. A status that did not occur has no slice to
 * see and no row to read; padding the legend with `Planlanıyor 0` is how a chart starts
 * describing its own schema instead of the window.
 */
export function Pie({
  slices,
  total,
  totalLabel,
  caption,
  label,
}: {
  slices: Slice[];
  /** What the ring is a breakdown of. Printed in the hole and used as the denominator. */
  total: number;
  totalLabel: string;
  caption: string;
  label: string;
}) {
  const drawn = slices.filter((slice) => slice.value > 0);
  const sum = drawn.reduce((acc, slice) => acc + slice.value, 0);
  if (drawn.length === 0 || sum <= 0) return null;

  let offset = 0;
  const arcs = drawn.map((slice) => {
    const length = (slice.value / sum) * C;
    const arc = { slice, length: Math.max(length - GAP, 0.6), offset };
    offset += length;
    return arc;
  });

  return (
    <div className="panel-pie">
      <div className="panel-pie__ring">
        <svg viewBox="0 0 140 140" role="img" aria-label={`${label}: ${drawn.map((s) => `${s.label} ${s.value}`).join(', ')}`}>
          {/*
            Two groups, not one. The CSS entrance animation sets `transform` on the outer
            group, and a CSS transform on an SVG element REPLACES the `transform`
            attribute rather than composing with it — one group meant the ring lost its
            own translate/rotate the moment the animation touched it, and drew as a
            quarter arc off the left edge.
          */}
          <g className="panel-pie__arcs">
            <g transform="translate(70 70) rotate(-90)">
              <circle r={R} fill="none" stroke="var(--bg-subtle)" strokeWidth="20" />
              {arcs.map(({ slice, length, offset: start }, index) => (
                <circle
                  key={slice.key}
                  className="panel-pie__arc"
                  style={delay(index)}
                  r={R}
                  fill="none"
                  stroke={slice.color}
                  strokeWidth="20"
                  strokeDasharray={`${length} ${C - length}`}
                  strokeDashoffset={-start}
                />
              ))}
            </g>
          </g>
          {/* The hole is not decoration: it holds the denominator every slice is a share of. */}
          <text className="panel-pie__total" x="70" y="68" textAnchor="middle">
            {total}
          </text>
          <text className="panel-pie__unit" x="70" y="86" textAnchor="middle">
            {totalLabel}
          </text>
        </svg>
      </div>
      <ul className="panel-legend">
        {drawn.map((slice) => (
          <li className="panel-legend__row" key={slice.key}>
            <span className="panel-legend__dot" style={{ background: slice.color }} aria-hidden />
            <span className="panel-legend__label">{slice.label}</span>
            <span className="panel-legend__value t-mono">{slice.value}</span>
            <span className="panel-legend__share t-mono">{percent(slice.value / sum)}</span>
          </li>
        ))}
        {/*
          The panel's own arithmetic check. `runs.total` and the sum of `runs.byStatus`
          come out of two different queries, and a chart drawn over one while its heading
          names the other is the quiet kind of wrong. Printed only when they disagree.
        */}
        {sum !== total && (
          <li className="panel-legend__row panel-legend__row--note">
            <span className="panel-legend__label">
              Durum kaydı toplamı {sum}; başlıktaki {total} ile eşleşmiyor.
            </span>
          </li>
        )}
      </ul>
      <p className="t-caption panel-pie__caption">{caption}</p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Funnel

/**
 * The gate, narrowing. Four stages, each contained in the one above it, then the split
 * of the gated steps into the five ways they ended.
 *
 * <p>The bars are centred so the shape reads as a funnel rather than as a left-aligned
 * bar chart that happens to descend — the containment is the claim, and a shape that
 * tapers from both sides is the one people already read that way.
 *
 * <p>`unaccounted` is drawn as a gap at the end of the split bar and written out under
 * it. It is normally zero; when it is not, the chart says so instead of stretching to
 * fit. See lib/funnel.ts.
 */
/**
 * Left edge and width for each segment of a 100-unit stacked bar.
 *
 * <p>Offsets accumulate over the *un-shortened* widths, so the hairline between segments
 * never adds up into a visible drift: 29 slices of a bar would otherwise finish 12 units
 * short of the end and the reader would read the gap as missing data.
 */
function stack(slices: FunnelSlice[]): { slice: FunnelSlice; x: number; width: number }[] {
  let x = 0;
  return slices.map((slice) => {
    const width = slice.share * 100;
    const seg = { slice, x, width: Math.max(width - 0.4, 0.4) };
    x += width;
    return seg;
  });
}

export function Funnel({
  stages,
  slices,
  gated,
  unaccounted,
  caption,
}: {
  stages: FunnelStage[];
  slices: FunnelSlice[];
  gated: number;
  unaccounted: number;
  caption: string;
}) {
  const drawn = slices.filter((slice) => slice.value > 0);
  return (
    <div className="panel-funnel">
      <ol className="panel-funnel__stages">
        {stages.map((stage, index) => (
          <li className="panel-funnel__stage" key={stage.key}>
            <span className="panel-funnel__label">{stage.label}</span>
            <svg
              className="panel-funnel__track"
              viewBox="0 0 100 14"
              preserveAspectRatio="none"
              role="img"
              aria-label={`${stage.label}: ${stage.value}${
                stage.ofPrevious == null ? '' : `, bir üst aşamanın ${percent(stage.ofPrevious)}'i`
              }`}
            >
              <rect
                className="panel-funnel__fill"
                style={delay(index)}
                x={(100 - stage.share * 100) / 2}
                y="0"
                width={Math.max(stage.share * 100, 0.8)}
                height="14"
                rx="3"
                fill="var(--chart-flow)"
              />
            </svg>
            <span className="panel-funnel__value t-mono">{stage.value}</span>
            {/* The number the funnel exists to show: what each step of the narrowing cost. */}
            <span className="panel-funnel__step t-mono">
              {stage.ofPrevious == null ? '' : percent(stage.ofPrevious)}
            </span>
          </li>
        ))}
      </ol>

      {drawn.length > 0 && (
        <div className="panel-funnel__split">
          <svg
            className="panel-funnel__bar"
            viewBox="0 0 100 14"
            preserveAspectRatio="none"
            role="img"
            aria-label={`Onaya düşen ${gated} adımın sonucu: ${drawn
              .map((s) => `${s.label} ${s.value}`)
              .join(', ')}`}
          >
            <rect x="0" y="0" width="100" height="14" rx="3" fill="var(--bg-subtle)" />
            {stack(drawn).map(({ slice, x, width }, index) => (
              <rect
                key={slice.key}
                className="panel-funnel__seg"
                style={delay(index)}
                x={x}
                y="0"
                width={width}
                height="14"
                rx="3"
                fill={slice.color}
              />
            ))}
          </svg>
          <ul className="panel-legend panel-legend--wide">
            {drawn.map((slice) => (
              <li className="panel-legend__row" key={slice.key}>
                <span className="panel-legend__dot" style={{ background: slice.color }} aria-hidden />
                <span className="panel-legend__label">{slice.label}</span>
                <span className="panel-legend__value t-mono">{slice.value}</span>
                <span className="panel-legend__share t-mono">{percent(slice.share)}</span>
              </li>
            ))}
          </ul>
          {unaccounted !== 0 && (
            <p className="t-caption panel-note">
              Onaya düşen {gated} adımın {Math.abs(unaccounted)} tanesi bu kovaların hiçbirinde
              değil; grafik eksiği kapatmıyor.
            </p>
          )}
        </div>
      )}
      <p className="t-caption panel-funnel__caption">{caption}</p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Bars

export type BarRow = {
  key: string;
  label: string;
  value: number;
  display: string;
  color: string;
  mono?: boolean;
};

/**
 * Hand-drawn bars, for the two lists that are magnitudes and nothing else: calls per tool
 * and calls per model.
 *
 * <p>They are one neutral colour rather than a rainbow, and that is a claim: a tool id is
 * not a state, and colouring six of them differently would invent six meanings the reader
 * then has to hold. The length compares them; the label names them; the number settles it.
 */
export function Bars({ rows, caption }: { rows: BarRow[]; caption: string }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return (
    <div className="panel-bars">
      {rows.map((row, index) => (
        <div className="panel-bar" key={row.key}>
          <span className={row.mono ? 'panel-bar__label t-mono' : 'panel-bar__label'}>{row.label}</span>
          <svg
            className="panel-bar__track"
            viewBox="0 0 100 8"
            preserveAspectRatio="none"
            role="img"
            aria-label={`${row.label}: ${row.display}`}
          >
            <rect x="0" y="0" width="100" height="8" rx="4" fill="var(--bg-subtle)" />
            {row.value > 0 && (
              <rect
                className="panel-bar__fill"
                style={delay(index)}
                x="0"
                y="0"
                width={Math.max((row.value / max) * 100, 1.5)}
                height="8"
                rx="4"
                fill={row.color}
              />
            )}
          </svg>
          <span className="panel-bar__value">{row.display}</span>
        </div>
      ))}
      <p className="t-caption panel-bars__caption">{caption}</p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Paired bars

export type PairRow = { key: string; label: string; value: number; display: string; color: string };

/**
 * Two amounts on one scale, so a subtraction printed as three numbers is also visible as
 * two lengths.
 *
 * <p>Used for exactly one thing: what the window cost against what the same tokens would
 * have cost on the strong model. The bars share a maximum — drawn on their own scales they
 * would be the same length and the comparison would be a lie told with a picture.
 *
 * <p>The counterfactual is the faint one. It did not happen, and the palette says so
 * rather than a caption having to.
 */
export function PairBars({ rows, footer }: { rows: PairRow[]; footer: string | null }) {
  /*
    The scale is the largest of the two amounts, NOT `Math.max(1, …)`. These are dollars
    and the interesting ones are fractions of a cent: floored at 1 the whole comparison
    drew as two slivers a few percent long, which is the picture saying "both were about
    nothing" over numbers that differ by a factor of two.
  */
  const values = rows.map((row) => (Number.isFinite(row.value) ? Math.max(row.value, 0) : 0));
  const max = Math.max(...values, Number.MIN_VALUE);
  return (
    <div className="panel-pair">
      {rows.map((row, index) => (
        <div className="panel-pair__row" key={row.key}>
          <span className="t-label panel-pair__label">{row.label}</span>
          <svg
            className="panel-pair__track"
            viewBox="0 0 100 10"
            preserveAspectRatio="none"
            role="img"
            aria-label={`${row.label}: ${row.display}`}
          >
            <rect x="0" y="0" width="100" height="10" rx="3" fill="var(--bg-subtle)" />
            {Number.isFinite(row.value) && row.value > 0 && (
              <rect
                className="panel-bar__fill"
                style={delay(index)}
                x="0"
                y="0"
                width={Math.max((row.value / max) * 100, 1.5)}
                height="10"
                rx="3"
                fill={row.color}
              />
            )}
          </svg>
          <span className="panel-pair__value t-mono">{row.display}</span>
        </div>
      ))}
      {footer != null && <p className="panel-pair__footer">{footer}</p>}
    </div>
  );
}
