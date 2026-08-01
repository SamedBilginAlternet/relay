import { BarChart3, CircleSlash, MessageSquareX, RefreshCw, TriangleAlert, Wrench } from 'lucide-react';
import { motion, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { EmptyState } from '../components/EmptyState';
import { getPanelSource } from '../data/PanelSource';
import { formatDateTime, formatTokens, formatUsd } from '../lib/format';
import { enterProps } from '../lib/motion';
import type { PanelRange, PanelReport } from '../types/panel';
import '../styles/panel.css';

/** Turkish labels for the run statuses, in the order the server sends them. */
const STATUS_LABEL: Record<string, string> = {
  planning: 'Planlanıyor',
  awaiting_approval: 'Onay bekliyor',
  running: 'Çalışıyor',
  done: 'Tamamlandı',
  failed: 'Hata',
  cancelled: 'İptal edildi',
};

/** DESIGN.md §1 — status colour is never the only carrier; every bar is labelled too. */
const STATUS_COLOR: Record<string, string> = {
  planning: 'var(--fg-faint)',
  awaiting_approval: 'var(--warn)',
  running: 'var(--info)',
  done: 'var(--success)',
  failed: 'var(--danger)',
  cancelled: 'var(--fg-muted)',
};

type Preset = '7' | '30' | 'today' | 'custom';

function localDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function daysAgo(days: number): string {
  return localDay(new Date(Date.now() - days * 86_400_000));
}

function percent(ratio: number): string {
  if (!Number.isFinite(ratio)) return '—';
  return `%${Math.round(ratio * 1000) / 10}`;
}

/** `animation-delay` as a custom property, so reduced motion can zero it out in CSS. */
function delay(index: number): CSSProperties {
  return { '--panel-delay': `${Math.min(index, 8) * 40}ms` } as CSSProperties;
}

/**
 * The flow panel: what ran, what a human had to clear, what was turned down and why,
 * and what all of it cost.
 *
 * <p>Two rules shape this screen. First, it costs nothing to open — the whole answer is
 * one `GET /api/panel`, and behind that are five aggregate queries and no model call.
 * The Groq quota has run dry on the live box before, and the screen that reports on the
 * budget must not be the one that spends it.
 *
 * <p>Second, an empty range says so. There is no chart drawn out of zeros here: if the
 * window holds no runs, the reader is told that, because a flat chart looks like a
 * measurement and "nothing happened" is not one.
 */
export function PanelScreen() {
  const reduce = useReducedMotion();
  const [preset, setPreset] = useState<Preset>('7');
  const [range, setRange] = useState<PanelRange>({});
  const [report, setReport] = useState<PanelReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (next: PanelRange) => {
    setLoading(true);
    setError(null);
    try {
      setReport(await getPanelSource().report(next));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Panel yüklenemedi.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(range);
  }, [load, range]);

  const choose = (next: Preset) => {
    setPreset(next);
    if (next === '7') setRange({});
    else if (next === '30') setRange({ from: daysAgo(29), to: localDay(new Date()) });
    else if (next === 'today') setRange({ from: localDay(new Date()), to: localDay(new Date()) });
  };

  const editBound = (key: 'from' | 'to', value: string) => {
    setPreset('custom');
    setRange((current) => {
      const next = { ...current, [key]: value || undefined };
      // A half-open custom range is legal on the server, but a screen that sends only
      // one bound quietly falls back to "last 7 days" for the other one; spell it out.
      if (key === 'from' && !next.to) next.to = localDay(new Date());
      if (key === 'to' && !next.from) next.from = daysAgo(7);
      return next;
    });
  };

  const statuses = useMemo(() => {
    const byStatus = report?.runs.byStatus ?? {};
    const keys = [...Object.keys(STATUS_LABEL), ...Object.keys(byStatus).filter((k) => !(k in STATUS_LABEL))];
    return keys.map((key) => ({
      key,
      label: STATUS_LABEL[key] ?? key,
      color: STATUS_COLOR[key] ?? 'var(--fg-faint)',
      count: byStatus[key] ?? 0,
    }));
  }, [report]);

  const empty = !!report && report.runs.total === 0 && report.approvals.steps === 0;
  const fromLabel = report ? formatDateTime(report.from) : '';
  // `to` is exclusive on the wire. Printing it as-is turns "up to and including 31 July"
  // into "1 August 00:00", which reads as a day the reader did not ask for.
  const toLabel = useMemo(() => {
    const end = Date.parse(report?.to ?? '');
    return Number.isNaN(end) ? '' : formatDateTime(new Date(end - 1000).toISOString());
  }, [report]);

  return (
    <div className="page">
      <div className="page__inner">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Akış paneli</h1>
            <p className="t-caption">
              Seçilen aralıkta kaç iş çalıştı, kaçı onaya düştü, ne reddedildi ve ne kadara mal
              oldu. Tamamı veritabanından okunur — bu ekran hiçbir model çağrısı yapmaz.
            </p>
          </div>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            onClick={() => void load(range)}
            disabled={loading}
          >
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        <div className="panel-range">
          <div className="panel-range__presets" role="group" aria-label="Hazır aralıklar">
            {(
              [
                ['7', 'Son 7 gün'],
                ['30', 'Son 30 gün'],
                ['today', 'Bugün'],
              ] as [Preset, string][]
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                className="panel-range__preset"
                aria-pressed={preset === key}
                onClick={() => choose(key)}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="panel-range__custom">
            <label className="panel-range__field">
              <span className="t-label">Başlangıç</span>
              <input
                type="date"
                value={range.from ?? daysAgo(7)}
                max={range.to ?? localDay(new Date())}
                onChange={(e) => editBound('from', e.target.value)}
              />
            </label>
            <label className="panel-range__field">
              <span className="t-label">Bitiş</span>
              <input
                type="date"
                value={range.to ?? localDay(new Date())}
                min={range.from}
                onChange={(e) => editBound('to', e.target.value)}
              />
            </label>
          </div>
        </div>

        {error && (
          <div className="notice notice--danger">
            <TriangleAlert size={16} aria-hidden />
            <span>{error}</span>
          </div>
        )}

        {loading && !report && (
          <div className="panel-skeletons">
            <div className="skeleton" style={{ height: 92 }} />
            <div className="skeleton" style={{ height: 220, opacity: 0.7 }} />
            <div className="skeleton" style={{ height: 160, opacity: 0.45 }} />
          </div>
        )}

        {report && empty && (
          <EmptyState
            Icon={CircleSlash}
            title="Bu aralıkta hiç akış yok"
            description={`${fromLabel} – ${toLabel} arasında çalışmış bir iş bulunamadı. Aralığı genişlet ya da sohbet ekranından bir iş başlat.`}
          />
        )}

        {report && !empty && (
          <>
            <motion.section className="panel-kpis" aria-label="Aralık özeti" {...enterProps(0, reduce)}>
              <Kpi label="Akış" value={String(report.runs.total)} hint={`${fromLabel} – ${toLabel}`} />
              <Kpi
                label="Onay kapısına düşen adım"
                value={percent(report.approvals.gatedRatio)}
                hint={`${report.approvals.gated} / ${report.approvals.steps} adım`}
              />
              <Kpi
                label="Onay oranı"
                value={percent(report.approvals.approvalRate)}
                hint={`${report.approvals.approved} onay · ${report.approvals.rejected} red`}
              />
              <Kpi
                label="Maliyet"
                value={formatUsd(report.totals.costUsd)}
                hint={`${formatTokens(report.totals.tokens)} token`}
              />
            </motion.section>

            <div className="panel-two">
              <motion.section
                className="card panel-card"
                aria-labelledby="panel-status-h"
                {...enterProps(1, reduce)}
              >
                <h2 className="t-label" id="panel-status-h">
                  Durum kırılımı
                </h2>
                <Bars
                  rows={statuses.map((s) => ({
                    key: s.key,
                    label: s.label,
                    color: s.color,
                    value: s.count,
                    display: String(s.count),
                  }))}
                  caption={`Toplam ${report.runs.total} akış, durumlarına göre.`}
                />
              </motion.section>

              <motion.section
                className="card panel-card"
                aria-labelledby="panel-gate-h"
                {...enterProps(2, reduce)}
              >
                <h2 className="t-label" id="panel-gate-h">
                  Onay kapısı
                </h2>
                <Donut
                  slices={[
                    { key: 'approved', label: 'Onaylandı', value: report.approvals.approved, color: 'var(--success)' },
                    { key: 'rejected', label: 'Reddedildi', value: report.approvals.rejected, color: 'var(--danger)' },
                    { key: 'pending', label: 'Bekliyor', value: report.approvals.pending, color: 'var(--warn)' },
                  ]}
                  centerValue={percent(report.approvals.approvalRate)}
                  centerLabel="onay oranı"
                />
              </motion.section>
            </div>

            <motion.section className="card panel-card" aria-labelledby="panel-tools-h" {...enterProps(3, reduce)}>
              <h2 className="t-label" id="panel-tools-h">
                Araç başına çağrı ve maliyet
              </h2>
              {report.tools.length === 0 ? (
                <p className="t-caption panel-note">
                  <Wrench size={14} aria-hidden />
                  Bu aralıkta hiçbir araç çağrılmadı.
                </p>
              ) : (
                <Bars
                  rows={report.tools.map((tool) => ({
                    key: tool.toolName,
                    label: tool.toolName,
                    mono: true,
                    color: 'var(--accent)',
                    value: tool.calls,
                    display: `${tool.calls} çağrı · ${formatUsd(tool.costUsd)}`,
                  }))}
                  caption="Sağlayıcıya gerçekten giden adımlar; reddedilen bir adım hiç çağrı yapmaz."
                />
              )}
            </motion.section>

            <motion.section
              className="card panel-card"
              aria-labelledby="panel-rejections-h"
              {...enterProps(4, reduce)}
            >
              <h2 className="t-label" id="panel-rejections-h">
                Red gerekçeleri
              </h2>
              {report.rejections.length === 0 ? (
                <p className="t-caption panel-note">
                  <MessageSquareX size={14} aria-hidden />
                  Bu aralıkta reddedilen adım yok.
                </p>
              ) : (
                <ul className="panel-rejects">
                  {report.rejections.map((rejection) => (
                    <li key={rejection.stepId}>
                      <a className="panel-reject" href={`#/history/${rejection.runId}`}>
                        <span className="panel-reject__reason">
                          {rejection.reason ?? 'Gerekçe yazılmadan reddedildi.'}
                        </span>
                        <span className="panel-reject__meta">
                          <span className="panel-reject__step">{rejection.stepTitle ?? 'Adım'}</span>
                          {rejection.toolName && <code className="t-mono">{rejection.toolName}</code>}
                          <span>{formatDateTime(rejection.at)}</span>
                        </span>
                        {rejection.runGoal && <span className="panel-reject__goal">{rejection.runGoal}</span>}
                      </a>
                    </li>
                  ))}
                </ul>
              )}
            </motion.section>
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

function Kpi({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="panel-kpi">
      <span className="t-label">{label}</span>
      <strong className="panel-kpi__value">{value}</strong>
      <span className="panel-kpi__hint">{hint}</span>
    </div>
  );
}

type BarRow = { key: string; label: string; value: number; display: string; color: string; mono?: boolean };

/**
 * Hand-drawn bars. No chart library: one `<svg>` per row, `viewBox="0 0 100 8"` with
 * `preserveAspectRatio="none"` so the track stretches with the column while the labels
 * stay in ordinary HTML and keep their type scale.
 *
 * The bar is decoration — the number is printed next to it, so nothing here depends on
 * being able to read a length or a colour.
 */
function Bars({ rows, caption }: { rows: BarRow[]; caption: string }) {
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

type Slice = { key: string; label: string; value: number; color: string };

/**
 * Hand-drawn ring. `r = 15.9155` makes the circumference exactly 100, so a slice is
 * `stroke-dasharray="<percent> <rest>"` and the offset is the running total — no
 * trigonometry and no library.
 *
 * It fades in rather than sweeping: `stroke-dashoffset` is neither transform nor
 * opacity, and DESIGN.md §4 only allows those two.
 */
function Donut({
  slices,
  centerValue,
  centerLabel,
}: {
  slices: Slice[];
  centerValue: string;
  centerLabel: string;
}) {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0);
  let cursor = 0;
  const arcs = slices
    .filter((slice) => slice.value > 0)
    .map((slice) => {
      const share = (slice.value / total) * 100;
      const arc = { ...slice, share, offset: 25 - cursor };
      cursor += share;
      return arc;
    });

  return (
    <div className="panel-donut">
      <div className="panel-donut__ring">
        <svg
          viewBox="0 0 42 42"
          className="panel-donut__svg"
          role="img"
          aria-label={
            total === 0
              ? 'Onay kapısına düşen adım yok'
              : slices.map((slice) => `${slice.label}: ${slice.value}`).join(', ')
          }
        >
          <circle cx="21" cy="21" r="15.9155" fill="none" stroke="var(--bg-subtle)" strokeWidth="4" />
          {arcs.map((arc, index) => (
            <circle
              key={arc.key}
              className="panel-donut__arc"
              style={delay(index)}
              cx="21"
              cy="21"
              r="15.9155"
              fill="none"
              stroke={arc.color}
              strokeWidth="4"
              strokeDasharray={`${arc.share} ${100 - arc.share}`}
              strokeDashoffset={arc.offset}
            />
          ))}
        </svg>
        <div className="panel-donut__center" aria-hidden>
          <strong>{total === 0 ? '—' : centerValue}</strong>
          <span>{centerLabel}</span>
        </div>
      </div>
      <ul className="panel-legend">
        {slices.map((slice) => (
          <li key={slice.key}>
            <span className="panel-legend__dot" style={{ background: slice.color }} aria-hidden />
            <span className="panel-legend__label">{slice.label}</span>
            <span className="panel-legend__value">{slice.value}</span>
          </li>
        ))}
      </ul>
      {total === 0 && (
        <p className="t-caption panel-note">
          <BarChart3 size={14} aria-hidden />
          Bu aralıkta hiçbir adım onaya düşmedi.
        </p>
      )}
    </div>
  );
}
