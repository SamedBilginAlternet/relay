import { CircleSlash, Cpu, MessageSquareX, PencilLine, RefreshCw, ShieldQuestion, Wrench } from 'lucide-react';
import { motion, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { Bars, Funnel, PairBars, Pie } from '../components/PanelCharts';
import type { Slice } from '../components/PanelCharts';
import { TabStrip } from '../components/TabStrip';
import { getPanelSource } from '../data/PanelSource';
import { formatTokens, formatUsd } from '../lib/format';
import { approvalFunnel } from '../lib/funnel';
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

/**
 * The run statuses in chart colour, from the panel palette declared in panel.css.
 *
 * <p>DESIGN.md §1 — status colour is never the only carrier; every slice is named and
 * numbered in the legend beside it. And none of these is violet: on this product violet
 * means "this is a state you are in", and a slice of a pie is not one.
 */
const STATUS_COLOR: Record<string, string> = {
  planning: 'var(--chart-idle)',
  awaiting_approval: 'var(--chart-waiting)',
  running: 'var(--chart-touched)',
  done: 'var(--chart-done)',
  failed: 'var(--chart-refused)',
  cancelled: 'var(--chart-void)',
};

type Preset = '7' | '30' | 'today';

/**
 * Which question the screen is answering.
 *
 * <p>Thirteen sections stacked down one column measured 5076px against a 900px window on
 * the live box — 5.6 screens, with the claim the product is sold on (the same tokens
 * priced on the strong model) as the tenth thing on the page. They are not thirteen
 * answers; they are three, and the reader wants one at a time:
 *
 * <ul>
 *   <li>`akis` — how much ran, and how it ended.
 *   <li>`onay` — what the approval gate did, and what it turned down.
 *   <li>`maliyet` — where the tokens and the money went, and what the routing saved.
 * </ul>
 */
export type PanelTab = 'akis' | 'onay' | 'maliyet';

const TABS: PanelTab[] = ['akis', 'onay', 'maliyet'];

/**
 * The tab named in the address, or the first one.
 *
 * <p>In the query rather than in a path segment, for the same reason Akışlar puts it
 * there: `parseHash` splits on `/` and would read `#/panel/onay` as a second route it has
 * no case for. The query is dropped before routing, so this is invisible to the router
 * and cannot become a second definition of what `#/panel` means.
 */
export function tabFromHash(hash: string): PanelTab {
  const query = hash.split('?')[1];
  if (!query) return 'akis';
  const asked = new URLSearchParams(query).get('bakis');
  return TABS.find((tab) => tab === asked) ?? 'akis';
}

export function hashForTab(tab: PanelTab): string {
  return tab === 'akis' ? '#/panel' : `#/panel?bakis=${tab}`;
}

/**
 * The tab the address asks for, kept in step with the back button.
 *
 * <p>Held in the URL rather than in state alone: a tab that only exists in memory cannot
 * be linked to — and a panel view is a thing people link to, because it is the screen you
 * send someone when they ask what the gate is doing — does not survive a refresh, and
 * swallows Back.
 */
function useTabInHash(): [PanelTab, (tab: PanelTab) => void] {
  const [tab, setTab] = useState<PanelTab>(() =>
    typeof window === 'undefined' ? 'akis' : tabFromHash(window.location.hash),
  );

  useEffect(() => {
    const onChange = () => setTab(tabFromHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const choose = useCallback((next: PanelTab) => {
    const hash = hashForTab(next);
    if (window.location.hash === hash) {
      setTab(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [tab, choose];
}

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

/**
 * The flow panel: what ran, what a human had to clear, what was turned down and why,
 * and what all of it cost — three tabs, one question each.
 *
 * <p>Two rules shape this screen. First, it costs nothing to open — the whole answer is
 * one `GET /api/panel`, and behind that are five aggregate queries and no model call.
 * The Groq quota has run dry on the live box before, and the screen that reports on the
 * budget must not be the one that spends it.
 *
 * <p>Second, an empty range says so. There is no chart drawn out of zeros here: if the
 * window holds no runs, the reader is told that, because a flat chart looks like a
 * measurement and "nothing happened" is not one. The same rule runs one level down — a
 * status with no runs has no slice, and a gate nobody reached has no funnel.
 */
export function PanelScreen() {
  const reduce = useReducedMotion();
  const [preset, setPreset] = useState<Preset>('7');
  const [range, setRange] = useState<PanelRange>({});
  const [report, setReport] = useState<PanelReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [tab, choose] = useTabInHash();

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

  const chooseRange = (next: Preset) => {
    setPreset(next);
    if (next === '7') setRange({});
    else if (next === '30') setRange({ from: daysAgo(29), to: localDay(new Date()) });
    else if (next === 'today') setRange({ from: localDay(new Date()), to: localDay(new Date()) });
  };

  const statuses = useMemo<Slice[]>(() => {
    const byStatus = report?.runs.byStatus ?? {};
    const keys = [
      ...Object.keys(STATUS_LABEL),
      ...Object.keys(byStatus).filter((k) => !(k in STATUS_LABEL)),
    ];
    return keys.map((key) => ({
      key,
      label: STATUS_LABEL[key] ?? key,
      color: STATUS_COLOR[key] ?? 'var(--chart-idle)',
      value: byStatus[key] ?? 0,
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
      <div className="page__inner page__inner--app panel-shell">
        {/*
          Title, range and refresh on one row. They used to be two blocks stacked, which
          cost ~120px of a 900px window before a single figure was drawn — and the range
          switch is not a section, it is the argument every figure below takes.
        */}
        <div className="page__head panel-head">
          <div className="page__head-text panel-head__text">
            <h1 className="t-title">Akış paneli</h1>
            <p className="t-caption">
              Ne çalıştı, ne onaya düştü, ne kadara mal oldu. Tamamı veritabanından okunur —
              bu ekran hiçbir model çağrısı yapmaz.
            </p>
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
                  onClick={() => chooseRange(key)}
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

        {error != null && <LoadError error={error} onRetry={() => void load(range)} />}

        {loading && !report && (
          <div className="panel-skeletons">
            <div className="skeleton" style={{ height: 44 }} />
            <div className="skeleton" style={{ height: 300, opacity: 0.7 }} />
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
            {/*
              Three destinations, not three sections. Only the chosen view is built, so
              the other two are not in the document at all — the reason the tabs exist is
              that all three were stacked and the page was 5.6 screens tall.

              No counts on these tabs. The one number that could sit here is the steps
              standing at the gate, and the sidebar already carries a waiting count over a
              different population (runs, not steps); two nearly-equal numbers labelled the
              same way is worse than one number in one place.
            */}
            <TabStrip
              label="Panel görünümleri"
              current={tab}
              onChoose={choose}
              tabs={[
                { id: 'akis', label: 'Akış', hint: 'Ne çalıştı ve nasıl bitti' },
                { id: 'onay', label: 'Onay', hint: 'Onay kapısı ne yaptı, ne reddedildi' },
                { id: 'maliyet', label: 'Maliyet', hint: 'Token, para ve model yönlendirmesi' },
              ]}
            />

            <motion.div
              key={tab}
              className="panel-view"
              role="tabpanel"
              id={`tabpanel-${tab}`}
              aria-labelledby={`tab-${tab}`}
              {...enterProps(0, reduce)}
            >
              {tab === 'akis' && (
                <FlowView report={report} statuses={statuses} fromLabel={fromLabel} toLabel={toLabel} />
              )}
              {tab === 'onay' && <GateView report={report} />}
              {tab === 'maliyet' && <CostView report={report} />}
            </motion.div>
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

/**
 * How much ran, and how it ended.
 *
 * <p>`runs.byStatus` is six parts of one whole and nothing else on the panel is, so it is
 * the one dataset that earns a pie. Beside it, the four figures that size the window —
 * they are what a reader checks the pie against, and putting them a scroll away was how
 * the old screen made two facts about the same 214 runs look like two subjects.
 */
function FlowView({
  report,
  statuses,
  fromLabel,
  toLabel,
}: {
  report: PanelReport;
  statuses: Slice[];
  fromLabel: string;
  toLabel: string;
}) {
  return (
    <div className="panel-grid panel-grid--flow">
      <section className="card panel-card" aria-labelledby="panel-status-h">
        <h2 className="t-label" id="panel-status-h">
          Durum kırılımı — {report.runs.total} akış
        </h2>
        <Pie
          slices={statuses}
          total={report.runs.total}
          totalLabel="akış"
          label="Durum kırılımı"
          caption="Hiç görülmemiş bir durum çizilmez; halkadaki her dilim en az bir akış."
        />
      </section>

      <section className="card panel-card" aria-labelledby="panel-window-h">
        <h2 className="t-label" id="panel-window-h">
          Bu aralıkta
        </h2>
        <div className="panel-figures">
          <Figure label="Akış" value={String(report.runs.total)} hint={`${fromLabel} – ${toLabel}`} />
          <Figure
            label="Adım"
            value={String(report.approvals.steps)}
            hint={`${report.approvals.gated} tanesi onay kapısına düştü`}
          />
          <Figure
            label="Onay kapısına düşen adım"
            value={percent(report.approvals.gatedRatio)}
            hint={`${report.approvals.gated} / ${report.approvals.steps} adım`}
          />
          <Figure
            label="Maliyet"
            value={formatUsd(report.totals.costUsd)}
            hint={`${formatTokens(report.totals.tokens)} token`}
          />
        </div>
        <p className="t-caption">
          Aynı pencere, dört sayı. Kırılımı Maliyet sekmesinde; kapının ne yaptığı Onay
          sekmesinde.
        </p>
      </section>
    </div>
  );
}

/**
 * The gate: the funnel, and the evidence.
 *
 * <p>`approvals` is the one dataset on this screen whose fields are nested — every gated
 * step is a step, every decision was gated, every approval was a decision — so it is drawn
 * as the shape that says so. Five bars of equal footing invited a reader to hold 71
 * approvals against 394 steps, which is not a ratio anybody should read off this screen.
 */
function GateView({ report }: { report: PanelReport }) {
  const gate = report.approvals;
  const funnel = useMemo(() => approvalFunnel(gate), [gate]);
  const decisions = gate.approved + gate.rejected;

  return (
    <div className="panel-grid panel-grid--gate">
      <section className="card panel-card panel-card--gate" aria-labelledby="panel-gate-h">
        <div className="panel-card__head">
          <h2 className="t-label" id="panel-gate-h">
            Onay kapısı — {gate.steps} adım
          </h2>
          {/*
            Three numbers under the rate, not one. "%89 onaylandı" was true and said
            nothing: it counted an approval where the human rewrote the channel the same
            as one where they read a line and pressed the button (#54).
          */}
          <Figure
            label="Onay oranı"
            value={percent(gate.approvalRate)}
            hint={`${gate.approvedAsIs} onay · ${gate.approvedWithEdit} düzeltilip onay · ${gate.rejected} red`}
            tight
          />
        </div>

        {gate.gated === 0 ? (
          <p className="t-caption panel-note">
            <ShieldQuestion size={14} aria-hidden />
            Bu aralıkta hiçbir adım onaya düşmedi.
          </p>
        ) : (
          <Funnel
            stages={funnel.stages}
            slices={funnel.slices}
            gated={funnel.gated}
            unaccounted={funnel.unaccounted}
            caption={`Her aşama bir üstünün içinde. "Akış durdurulduğu için kapandı" bir insan kararı değil — onay oranı bu ${gate.cancelled} adımı saymaz.`}
          />
        )}

        {/*
          The sentence the issue asked for, and the reason the split is worth the screen
          space: a gate that only says yes or no is a speed bump, and one that changes the
          payload is doing work. Printed only when there is a decision behind it —
          "%0'ında" out of nothing is not a measurement.
        */}
        {decisions > 0 && (
          <p className="t-caption panel-note">
            <PencilLine size={14} aria-hidden />
            İnsan, {decisions} kararın {percent(gate.editRate)}'inde gönderilecek değeri
            değiştirdi.
          </p>
        )}
      </section>

      <section className="card panel-card panel-card--list" aria-labelledby="panel-rejections-h">
        <h2 className="t-label" id="panel-rejections-h">
          Red gerekçeleri
        </h2>
        <DecisionList
          lines={report.rejections}
          empty="Bu aralıkta reddedilen adım yok."
          fallbackReason="Gerekçe yazılmadan reddedildi."
          /*
            A refusal on a run that somebody stopped later is still a refusal, so it stays
            here — with a tag saying where it ended up, not moved out of the list that has
            to carry the gate's evidence.
          */
          tagCancelledRuns
        />
      </section>

      {/*
        Its own block, and that is the whole point of #54. These lines used to fill "Red
        gerekçeleri": four of six were one person pressing Durdur, so the one list that
        proves the approval gate earns its friction was mostly not about the gate at all.
        Nothing is hidden — the count is in the funnel above and every line is still one
        click from its run.
      */}
      {report.cancellations.length > 0 && (
        <section className="card panel-card panel-card--list" aria-labelledby="panel-cancels-h">
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
        </section>
      )}
    </div>
  );
}

/**
 * Where the tokens and the money went.
 *
 * <p>This is the product's pitch, and on the old screen it was the eleventh thing on the
 * page. It is a whole tab now: which model carried the volume, what each tool cost, and
 * the one comparison the panel is allowed to draw.
 */
function CostView({ report }: { report: PanelReport }) {
  return (
    <div className="panel-grid panel-grid--cost">
      {/*
        Where the money went, and what the routing did about it. The panel used to print
        one total — true, and silent on the claim the product is sold on. Both halves come
        out of the same rows (`steps.model is not null`), so the column adds up to the line
        under it and a reader can check the subtraction by eye.
      */}
      <section className="card panel-card" aria-labelledby="panel-models-h">
        <div className="panel-card__head">
          <h2 className="t-label" id="panel-models-h">
            Model başına çağrı ve maliyet
          </h2>
          <Figure
            label="Aralığın toplamı"
            value={formatUsd(report.totals.costUsd)}
            hint={`${formatTokens(report.totals.tokens)} token`}
            tight
          />
        </div>
        {report.models.length === 0 ? (
          /*
            The honest empty state. `steps.model` may not be deployed yet, and there is no
            chart to draw out of that — saying which model answered is not something this
            screen can infer.
          */
          <p className="t-caption panel-note">
            <Cpu size={14} aria-hidden />
            Bu aralıkta hangi modelin cevapladığı kayıtlı değil.
          </p>
        ) : (
          /*
            Its own wrapper so the bars can carry a wider label column than the tool
            block's: a provider-qualified model id is regularly longer than a tool name,
            and this block exists to name the model. See panel.css.
          */
          <div className="panel-models">
            <Bars
              rows={report.models.map((model) => ({
                key: model.model,
                label: model.model,
                mono: true,
                color: 'var(--chart-volume)',
                value: model.calls,
                display: `${model.calls} çağrı · ${formatTokens(model.tokens)} token · ${formatUsd(model.costUsd)}`,
              }))}
              caption="Modelin cevapladığı kaydedilmiş adımlar. Adıma bağlı olmayan model kullanımı (planlama, özet) bu tabloda görünmez — üstteki toplam maliyet onu da içerir."
            />
            <Routing routing={report.routing} />
          </div>
        )}
      </section>

      <section className="card panel-card" aria-labelledby="panel-tools-h">
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
              color: 'var(--chart-volume)',
              value: tool.calls,
              display: `${tool.calls} çağrı · ${formatUsd(tool.costUsd)}`,
            }))}
            caption="Sağlayıcıya gerçekten giden adımlar; reddedilen bir adım hiç çağrı yapmaz."
          />
        )}
      </section>
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
 *
 * <p>The list scrolls inside its card rather than pushing the page down. The server sends
 * up to 50 of each, and there is no honest fixed height for a list whose length is a fact
 * about the window: capping it at five would be the screen deciding which refusals count.
 * The count above says how many there are, so a short pane never reads as a short list.
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
    <>
      <p className="t-caption panel-list__count">{lines.length} kayıt</p>
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
    </>
  );
}

/**
 * The one comparison the panel is allowed to draw: what the window cost, what the same
 * tokens would have cost priced entirely on the strong model, and the gap.
 *
 * <p>Two bars on one scale, because three numbers in a row make the reader do the
 * subtraction and two lengths on a shared maximum show it. Drawn on separate scales they
 * would be the same length, which is a lie told with a picture — hence `PairBars`, which
 * takes one maximum for the set.
 *
 * <p>Everything it is tempting to put here is missing on purpose. No time saved, no
 * productivity multiplier, no "%N cheaper" — none of those can be derived from a token
 * count and a price list, and this screen is only worth opening because every figure on it
 * can be traced back to a row.
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
      <PairBars
        rows={[
          {
            key: 'paid',
            label: 'Bu aralıkta ödenen',
            value: routing.costUsd,
            display: formatUsd(routing.costUsd),
            color: 'var(--chart-paid)',
          },
          {
            key: 'premium',
            label: 'Tamamı güçlü modelde olsaydı',
            value: routing.premiumCostUsd,
            display: formatUsd(routing.premiumCostUsd),
            color: 'var(--chart-counterfactual)',
          },
        ]}
        footer={null}
      />
      <p className="panel-routing__diff">
        <span className="t-label">Fark</span>
        <span className="panel-routing__value t-mono">{formatUsd(routing.differenceUsd)}</span>
      </p>
      <p className="t-caption">
        Aynı {routing.calls} çağrının aynı {formatTokens(routing.tokens)} tokenı üzerinden, iki
        fiyat listesiyle. Kaydedilen token sayısı ve yapılandırılmış fiyat dışında bir şey
        hesaba girmez.
        {/*
          Coverage, printed only when there is any to report. A step that touched the
          offline stub has no honest premium figure, and its cost is out of both sides
          rather than sitting on one of them — #119. Saying how many were left out is what
          keeps this line from reading like it covers the whole window.
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

/** A label, a figure and the line that says what the figure is made of. */
function Figure({
  label,
  value,
  hint,
  tight = false,
}: {
  label: string;
  value: string;
  hint: string;
  tight?: boolean;
}) {
  return (
    <div className={tight ? 'panel-figure panel-figure--tight' : 'panel-figure'}>
      <span className="t-label">{label}</span>
      <strong className="panel-figure__value">{value}</strong>
      <span className="panel-figure__hint">{hint}</span>
    </div>
  );
}
