import {
  BarChart3,
  CircleSlash,
  Cpu,
  MessageSquareX,
  PencilLine,
  RefreshCw,
  Wrench,
} from 'lucide-react';
import { motion, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { getPanelSource } from '../data/PanelSource';
import { formatTokens, formatUsd } from '../lib/format';
import { enterProps } from '../lib/motion';
import type { PanelRange, PanelRejection, PanelReport, PanelRouting } from '../types/panel';
import '../styles/panel.css';
import '../styles/screens.css';

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

type Preset = '7' | '30' | 'today';

function localDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function daysAgo(days: number): string {
  return localDay(new Date(Date.now() - days * 86_400_000));
}

/**
 * One date format on the screen, and it is the product's own.
 *
 * <p>The native `input[type=date]` drew `07/25/2026` next to the app's own
 * "25 Tem", because the control follows `navigator.language` (en-US on the demo
 * machine) and not `lang="tr"`. Those inputs are gone; this is what is left.
 */
function panelDate(iso: string | null, withTime = false): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('tr-TR', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    ...(withTime ? { hour: '2-digit', minute: '2-digit' } : {}),
  });
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
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async (next: PanelRange) => {
    setLoading(true);
    setError(null);
    try {
      setReport(await getPanelSource().report(next));
    } catch (err) {
      setError(err);
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
  const fromLabel = report ? panelDate(report.from) : '';
  // `to` is exclusive on the wire. Printing it as-is turns "up to and including 31 July"
  // into "1 August 00:00", which reads as a day the reader did not ask for.
  const toLabel = useMemo(() => {
    const end = Date.parse(report?.to ?? '');
    return Number.isNaN(end) ? '' : panelDate(new Date(end - 1000).toISOString());
  }, [report]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
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
          {report && (
            <p className="t-caption panel-range__label">
              {fromLabel} – {toLabel}
            </p>
          )}
        </div>

        {error != null && <LoadError error={error} onRetry={() => void load(range)} />}

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
              {/*
                Three numbers under the rate, not one. "%89 onaylandı" was true and said
                nothing: it counted an approval where the human rewrote the channel the
                same as one where they read a line and pressed the button (#54).
              */}
              <Kpi
                label="Onay oranı"
                value={percent(report.approvals.approvalRate)}
                hint={`${report.approvals.approvedAsIs} onay · ${report.approvals.approvedWithEdit} düzeltilip onay · ${report.approvals.rejected} red`}
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
                  Durum kırılımı — {report.runs.total} akış
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
                  Onay kapısı — {report.approvals.steps} adım
                </h2>
                {report.approvals.gated === 0 ? (
                  <p className="t-caption panel-note">
                    <BarChart3 size={14} aria-hidden />
                    Bu aralıkta hiçbir adım onaya düşmedi.
                  </p>
                ) : (
                  <Bars
                    rows={[
                      {
                        key: 'approved',
                        label: 'Onaylandı',
                        color: 'var(--success)',
                        value: report.approvals.approvedAsIs,
                        display: String(report.approvals.approvedAsIs),
                      },
                      {
                        key: 'approvedWithEdit',
                        label: 'Düzeltilip onaylandı',
                        color: 'var(--info)',
                        value: report.approvals.approvedWithEdit,
                        display: String(report.approvals.approvedWithEdit),
                      },
                      {
                        key: 'rejected',
                        label: 'Reddedildi',
                        color: 'var(--danger)',
                        value: report.approvals.rejected,
                        display: String(report.approvals.rejected),
                      },
                      {
                        key: 'cancelled',
                        label: 'Akış durdurulduğu için kapandı',
                        color: 'var(--fg-muted)',
                        value: report.approvals.cancelled,
                        display: String(report.approvals.cancelled),
                      },
                      {
                        key: 'pending',
                        label: 'Kararını bekliyor',
                        color: 'var(--warn)',
                        value: report.approvals.pending,
                        display: String(report.approvals.pending),
                      },
                    ]}
                    caption={`Onaya düşen ${report.approvals.gated} adım. "Akış durdurulduğu için kapandı" bir insan kararı değil — onay oranı bu adımları saymaz.`}
                  />
                )}
                {/*
                  The sentence the issue asked for, and the reason the split is worth the
                  screen space: a gate that only says yes or no is a speed bump, and one
                  that changes the payload is doing work. It is only printed when there is
                  a decision behind it — "%0'ında" out of nothing is not a measurement.
                */}
                {report.approvals.approved + report.approvals.rejected > 0 && (
                  <p className="t-caption panel-note">
                    <PencilLine size={14} aria-hidden />
                    İnsan, {report.approvals.approved + report.approvals.rejected} kararın{' '}
                    {percent(report.approvals.editRate)}'inde gönderilecek değeri değiştirdi.
                  </p>
                )}
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

            {/*
              Where the money went, and what the routing did about it. The panel used to
              print one total — true, and silent on the claim the product is sold on. Both
              halves come out of the same rows (`steps.model is not null`), so the column
              adds up to the line under it and a reader can check the subtraction by eye.
            */}
            <motion.section className="card panel-card" aria-labelledby="panel-models-h" {...enterProps(4, reduce)}>
              <h2 className="t-label" id="panel-models-h">
                Model başına çağrı ve maliyet
              </h2>
              {report.models.length === 0 ? (
                /*
                  The honest empty state. `steps.model` may not be deployed yet, and there
                  is no chart to draw out of that — saying which model answered is not
                  something this screen can infer.
                */
                <p className="t-caption panel-note">
                  <Cpu size={14} aria-hidden />
                  Bu aralıkta hangi modelin cevapladığı kayıtlı değil.
                </p>
              ) : (
                <>
                  <Bars
                    rows={report.models.map((model) => ({
                      key: model.model,
                      label: model.model,
                      mono: true,
                      color: 'var(--accent)',
                      value: model.calls,
                      display: `${model.calls} çağrı · ${formatTokens(model.tokens)} token · ${formatUsd(model.costUsd)}`,
                    }))}
                    caption="Modelin cevapladığı kaydedilmiş adımlar. Adıma bağlı olmayan model kullanımı (planlama, özet) bu tabloda görünmez — üstteki toplam maliyet onu da içerir."
                  />
                  <Routing routing={report.routing} />
                </>
              )}
            </motion.section>

            <motion.section
              className="card panel-card"
              aria-labelledby="panel-rejections-h"
              {...enterProps(5, reduce)}
            >
              <h2 className="t-label" id="panel-rejections-h">
                Red gerekçeleri
              </h2>
              <DecisionList
                lines={report.rejections}
                empty="Bu aralıkta reddedilen adım yok."
                fallbackReason="Gerekçe yazılmadan reddedildi."
                /*
                  A refusal on a run that somebody stopped later is still a refusal, so it
                  stays here — with a tag saying where it ended up, not moved out of the
                  list that has to carry the gate's evidence.
                */
                tagCancelledRuns
              />
            </motion.section>

            {/*
              Its own block, and that is the whole point of #54. These lines used to fill
              "Red gerekçeleri": four of six were one person pressing Durdur, so the one
              list that proves the approval gate earns its friction was mostly not about
              the gate at all. Nothing is hidden — the count is on the bar chart above and
              every line is still one click from its run.
            */}
            {report.cancellations.length > 0 && (
              <motion.section
                className="card panel-card"
                aria-labelledby="panel-cancels-h"
                {...enterProps(6, reduce)}
              >
                <h2 className="t-label" id="panel-cancels-h">
                  Durdurulan akışlarda kapanan adımlar
                </h2>
                <p className="t-caption panel-note">
                  <CircleSlash size={14} aria-hidden />
                  Bir akış durdurulduğunda tamamlanmamış adımları reddedilmiş olarak kapanır. Bunlar
                  kullanıcının o adım hakkında verdiği bir karar değil; onay oranına da girmezler.
                </p>
                <DecisionList
                  lines={report.cancellations}
                  empty="Bu aralıkta durdurulan akış yok."
                  fallbackReason="Akış durduruldu."
                  muted
                />
              </motion.section>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

/**
 * One list shape for both "Red gerekçeleri" and "Durdurulan akışlarda kapanan adımlar".
 *
 * <p>They are deliberately identical to read: the reader is being asked to compare two
 * piles that used to be one, and a different card for each would make the split look
 * like a judgement about which pile matters. Every line stays a link to its run —
 * a sentence nobody can check is an anecdote.
 */
function DecisionList({
  lines,
  empty,
  fallbackReason,
  tagCancelledRuns = false,
  muted = false,
}: {
  lines: PanelRejection[];
  empty: string;
  fallbackReason: string;
  tagCancelledRuns?: boolean;
  muted?: boolean;
}) {
  if (lines.length === 0) {
    return (
      <p className="t-caption panel-note">
        <MessageSquareX size={14} aria-hidden />
        {empty}
      </p>
    );
  }
  return (
    <ul className="panel-rejects">
      {lines.map((line) => (
        <li key={line.stepId}>
          <a
            className={muted ? 'panel-reject panel-reject--muted' : 'panel-reject'}
            href={`#/history/${line.runId}`}
          >
            <span className="panel-reject__reason">{line.reason ?? fallbackReason}</span>
            <span className="panel-reject__meta">
              <span className="panel-reject__step">{line.stepTitle ?? 'Adım'}</span>
              {line.toolName && <code className="t-mono">{line.toolName}</code>}
              <span>{panelDate(line.at, true)}</span>
              {tagCancelledRuns && line.runStatus === 'cancelled' && (
                <span className="panel-reject__tag">akış sonradan durduruldu</span>
              )}
            </span>
            {line.runGoal && <span className="panel-reject__goal">{line.runGoal}</span>}
          </a>
        </li>
      ))}
    </ul>
  );
}

/**
 * The one comparison line: what the window cost, what the same tokens would have cost
 * priced entirely on the strong model, and the gap between them.
 *
 * <p>Everything it is tempting to put here is missing on purpose. No time saved, no
 * productivity multiplier, no "%N cheaper" — none of those can be derived from a token
 * count and a price list, and this screen is only worth opening because every figure on
 * it can be traced back to a row. The three numbers are printed next to each other so the
 * subtraction is visible; the reader is not asked to trust it.
 *
 * <p>The difference is signed. If the strong model answered everything it is $0.000000,
 * and it says so rather than hiding the line.
 */
function Routing({ routing }: { routing: PanelRouting | null }) {
  if (!routing) {
    return (
      <p className="t-caption panel-note">
        <Cpu size={14} aria-hidden />
        Bu aralık için güçlü model karşılaştırması kayıtlı değil.
      </p>
    );
  }
  return (
    <div className="panel-routing">
      <dl className="panel-routing__figures">
        <div className="panel-routing__figure">
          <dt className="t-label">Bu aralıkta ödenen</dt>
          <dd className="panel-routing__value">{formatUsd(routing.costUsd)}</dd>
        </div>
        <div className="panel-routing__figure">
          <dt className="t-label">Tamamı güçlü modelde olsaydı</dt>
          <dd className="panel-routing__value">{formatUsd(routing.premiumCostUsd)}</dd>
        </div>
        <div className="panel-routing__figure panel-routing__figure--diff">
          <dt className="t-label">Fark</dt>
          <dd className="panel-routing__value">{formatUsd(routing.differenceUsd)}</dd>
        </div>
      </dl>
      <p className="t-caption">
        Aynı {routing.calls} çağrının aynı {formatTokens(routing.tokens)} tokenı üzerinden, iki
        fiyat listesiyle. Kaydedilen token sayısı ve yapılandırılmış fiyat dışında bir şey
        hesaba girmez.
        {/*
          Coverage, printed only when there is any to report. A step that touched the
          offline stub has no honest premium figure, and its cost is out of both sides
          rather than sitting on one of them — #119. Saying how many were left out is
          what keeps this line from reading like it covers the whole window.
        */}
        {routing.unpricedCalls > 0 && (
          <>
            {' '}
            {routing.unpricedCalls} çağrı fiyatlanamadığı için karşılaştırmanın iki tarafında da
            yok.
          </>
        )}
      </p>
    </div>
  );
}

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
