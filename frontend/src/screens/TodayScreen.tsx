import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
  CalendarDays,
  CheckCircle2,
  ClipboardList,
  GitPullRequest,
  Inbox,
  RefreshCw,
  Sparkles,
  TriangleAlert,
  Undo2,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SectionPanel, SectionTile } from '../components/BriefSections';
import type { SectionMeta } from '../components/BriefSections';
import { InsightCardView } from '../components/InsightCardView';
import { PriorityRow } from '../components/PriorityRow';
import { getBriefSource, RUN_SOURCE_KIND } from '../data';
import { formatDayMonth } from '../lib/format';
import { enterProps, expandProps } from '../lib/motion';
import { useRunStore } from '../store/runStore';
import { EMPTY_SECTION } from '../types/brief';
import type { Brief, BriefSectionKey, InsightCard, SuggestedAction } from '../types/brief';

type Props = { onNavigate: (hash: string) => void };

type Phase = 'loading' | 'refreshing' | 'ready' | 'error';

const SECTIONS: SectionMeta[] = [
  {
    key: 'inbox',
    title: 'Gelen kutusu',
    Icon: Inbox,
    connectLabel: 'Gmail',
    emptyText: 'Bugün yeni mail yok. Gelen kutun temiz.',
  },
  {
    key: 'work',
    title: 'Üstümdeki işler',
    Icon: ClipboardList,
    connectLabel: 'Jira',
    emptyText: 'Sana atanmış açık kayıt yok.',
  },
  {
    key: 'code',
    title: 'Kod',
    Icon: GitPullRequest,
    connectLabel: 'GitHub',
    emptyText: 'Review bekleyen PR ya da atanmış issue yok.',
  },
  {
    key: 'calendar',
    title: 'Takvim',
    Icon: CalendarDays,
    connectLabel: 'Google Calendar',
    emptyText: 'Bugün için planlanmış toplantı yok.',
  },
];

const EMPTY = EMPTY_SECTION;

/** Where the header badge went (issue: "canlı api yazısını sil"): the data
 *  source is a property of *the brief*, not of the whole product chrome. */
const SOURCE_NOTE =
  RUN_SOURCE_KIND === 'api' ? 'Canlı API' : 'Demo veri (senaryo)';

export function TodayScreen({ onNavigate }: Props) {
  const [brief, setBrief] = useState<Brief | null>(null);
  const [phase, setPhase] = useState<Phase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string[]>([]);
  const [busy, setBusy] = useState<{ cardId: string; tool: string } | null>(null);
  const [openKey, setOpenKey] = useState<BriefSectionKey | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const openRun = useRunStore((s) => s.openRun);
  const reduce = useReducedMotion();

  /* The strip sits low on the screen, so a panel opening below it can land
     off-screen — the click would look like it did nothing. Bring it in. */
  useEffect(() => {
    if (!openKey) return;
    const id = window.setTimeout(() => {
      panelRef.current?.scrollIntoView({
        behavior: reduce ? 'auto' : 'smooth',
        block: 'nearest',
      });
    }, 220);
    return () => window.clearTimeout(id);
  }, [openKey, reduce]);

  const load = useCallback(async (mode: 'initial' | 'refresh') => {
    setPhase(mode === 'initial' ? 'loading' : 'refreshing');
    setError(null);
    try {
      const source = getBriefSource();
      const next = mode === 'initial' ? await source.getBrief() : await source.refreshBrief();
      setBrief(next);
      setPhase('ready');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Brifing yüklenemedi.');
      setPhase('error');
    }
  }, []);

  useEffect(() => {
    void load('initial');
  }, [load]);

  const priority = useMemo(
    () => (brief?.priority ?? []).filter((c) => !dismissed.includes(c.id)),
    [brief?.priority, dismissed],
  );

  /*
    The big slot has to earn itself. A low-urgency FYI with nothing to run is
    not "what to do next" — blowing it up to a hero card just teaches people
    that the biggest thing on the screen means nothing. On a quiet day every
    insight is a row and the screen gets shorter, which is the honest answer.
  */
  const first: InsightCard | undefined = priority[0];
  const worthFocus =
    first != null && (first.urgency !== 'low' || first.suggestedActions.length > 0);
  const focus = worthFocus ? first : null;
  const rest = worthFocus ? priority.slice(1) : priority;

  const runAction = async (card: InsightCard, action: SuggestedAction) => {
    setBusy({ cardId: card.id, tool: action.tool });
    setError(null);
    try {
      const { runId } = await getBriefSource().startFromSuggestion(card.id, action);
      // Same engine as always: navigate into the run view and let it stream.
      onNavigate('#/sohbet');
      await openRun(runId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Akış başlatılamadı.');
    } finally {
      setBusy(null);
    }
  };

  const loading = phase === 'loading';
  const refreshing = phase === 'refreshing';
  const showBody = brief != null || loading;
  const openMeta = SECTIONS.find((m) => m.key === openKey) ?? null;

  const liveMessage =
    phase === 'loading'
      ? 'Bugünün brifingi hazırlanıyor.'
      : phase === 'refreshing'
        ? 'Brifing yenileniyor.'
        : phase === 'error'
          ? (error ?? 'Brifing yüklenemedi.')
          : brief
            ? `Brifing hazır. ${priority.length} öncelikli kart.`
            : '';

  return (
    <div className="page">
      <div className="page__inner brief">
        <motion.div className="brief-top" {...enterProps(0, reduce)}>
          <div className="brief-top__text">
            <h1 className="t-title">
              Bugün <span className="brief-top__dot" aria-hidden>·</span>{' '}
              <span className="brief-top__date">{formatDayMonth(brief?.date ?? null)}</span>
            </h1>
            <p className="brief-top__meta">
              <span
                className={`src-dot src-dot--${RUN_SOURCE_KIND}${phase === 'error' ? ' src-dot--down' : ''}`}
                aria-hidden
              />
              {phase === 'error' ? `${SOURCE_NOTE} — yanıt yok` : SOURCE_NOTE}
              <span className="brief-top__note">Öneriye basmadan hiçbir şey çalışmaz.</span>
            </p>
          </div>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            onClick={() => void load('refresh')}
            disabled={loading || refreshing}
          >
            <RefreshCw size={14} aria-hidden className={loading || refreshing ? 'spin' : undefined} />
            {refreshing ? 'Yenileniyor…' : 'Yenile'}
          </button>
        </motion.div>

        <p className="sr-only" role="status" aria-live="polite">
          {liveMessage}
        </p>

        {phase === 'error' && (
          <div className="notice notice--danger" role="alert">
            <TriangleAlert size={16} aria-hidden />
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
              <span>{error ?? 'Brifing yüklenemedi.'}</span>
              <span className="t-caption">
                Backend ayakta değilse <code className="t-mono">VITE_RUN_SOURCE=mock</code> ile demo
                verisiyle çalışabilirsin.
              </span>
              <button type="button" className="btn btn--outline btn--sm" onClick={() => void load('initial')}>
                Tekrar dene
              </button>
            </div>
          </div>
        )}

        {phase !== 'error' && error && (
          <div className="notice notice--danger" role="alert">
            <TriangleAlert size={16} aria-hidden />
            <span>{error}</span>
          </div>
        )}

        {/*
          If the brief never arrived there is nothing honest to show: empty
          sections would read as "nothing to do today", which is a lie.
          The error card above is the whole screen until it loads.
        */}
        {!showBody ? null : (
          <div className="brief-body">
            {/* ---------------- ÖNCELİKLİ ---------------- */}
            <section className="brief-prio" aria-labelledby="brief-priority-h">
              <div className="brief-prio__head">
                <h2 className="t-label" id="brief-priority-h">
                  <Sparkles size={12} aria-hidden /> Öncelikli
                </h2>
                {!loading && priority.length > 0 && (
                  <span className="t-caption">{priority.length} başlık</span>
                )}
                {!loading && dismissed.length > 0 && (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm brief-prio__undo"
                    onClick={() => setDismissed([])}
                  >
                    <Undo2 size={14} aria-hidden />
                    {dismissed.length} yoksayılanı geri al
                  </button>
                )}
              </div>

              {loading && (
                <div className="brief-prio__skeleton">
                  <div className="skeleton" style={{ height: 116 }} />
                  <div className="skeleton" style={{ height: 44, opacity: 0.6 }} />
                  <div className="skeleton" style={{ height: 44, opacity: 0.4 }} />
                </div>
              )}

              {!loading && focus && (
                <InsightCardView
                  key={focus.id}
                  card={focus}
                  index={0}
                  busyTool={busy?.cardId === focus.id ? busy.tool : null}
                  onAction={(c, a) => void runAction(c, a)}
                  onDismiss={(id) => setDismissed((cur) => [...cur, id])}
                />
              )}

              {!loading && rest.length > 0 && (
                <ul className="prow-list">
                  <AnimatePresence initial={false}>
                    {rest.map((card, i) => (
                      <PriorityRow
                        key={card.id}
                        card={card}
                        index={focus ? i + 1 : i}
                        busyTool={busy?.cardId === card.id ? busy.tool : null}
                        onAction={(c, a) => void runAction(c, a)}
                        onDismiss={(id) => setDismissed((cur) => [...cur, id])}
                      />
                    ))}
                  </AnimatePresence>
                </ul>
              )}

              {!loading && priority.length === 0 && (
                <p className="brief-prio__clear">
                  <CheckCircle2 size={16} aria-hidden />
                  {dismissed.length > 0
                    ? 'Öncelikli liste temizlendi.'
                    : 'Öne çıkan bir şey yok — bugün acil işaretlenen mail, PR ya da kayıt bulunmadı.'}
                </p>
              )}
            </section>

            {/* ---------------- Bölümler ---------------- */}
            <section className="brief-sec" aria-labelledby="brief-sections-h">
              <div className="brief-prio__head">
                <h2 className="t-label" id="brief-sections-h">
                  Bölümler
                </h2>
                <span className="t-caption">Sayıya bas, listesi açılsın</span>
              </div>

              <div className="tile-strip">
                {SECTIONS.map((meta, i) => (
                  <SectionTile
                    key={meta.key}
                    meta={meta}
                    index={i + 2}
                    section={brief?.[meta.key] ?? EMPTY}
                    loading={loading}
                    open={openKey === meta.key}
                    tileId={`tile-${meta.key}`}
                    panelId={`panel-${meta.key}`}
                    onToggle={() => setOpenKey((cur) => (cur === meta.key ? null : meta.key))}
                  />
                ))}
              </div>

              <AnimatePresence initial={false}>
                {openMeta && (
                  <motion.div key={openMeta.key} ref={panelRef} {...expandProps(reduce)}>
                    <SectionPanel
                      meta={openMeta}
                      section={brief?.[openMeta.key] ?? EMPTY}
                      tileId={`tile-${openMeta.key}`}
                      panelId={`panel-${openMeta.key}`}
                      onClose={() => setOpenKey(null)}
                      onGoToConnections={() => onNavigate('#/connections')}
                      onRetry={() => void load('refresh')}
                    />
                  </motion.div>
                )}
              </AnimatePresence>
            </section>
          </div>
        )}
      </div>
    </div>
  );
}
