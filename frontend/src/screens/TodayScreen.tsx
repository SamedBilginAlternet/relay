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
import { PlaybookShelf, SectionPanel, SectionTile } from '../components/BriefSections';
import type { SectionMeta } from '../components/BriefSections';
import { InsightCardView, SOURCE_META } from '../components/InsightCardView';
import { PriorityRow } from '../components/PriorityRow';
import { getBriefSource, RUN_SOURCE_KIND } from '../data';
import { getPlaybookSource } from '../data/PlaybookSource';
import type { Playbook } from '../data/PlaybookSource';
import { formatDayMonth } from '../lib/format';
import { enterProps, expandProps } from '../lib/motion';
import { useRunStore } from '../store/runStore';
import { EMPTY_SECTION } from '../types/brief';
import type {
  Brief,
  BriefHighlight,
  BriefHighlightSource,
  BriefSectionKey,
  InsightCard,
  SuggestedAction,
} from '../types/brief';

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

/* Same icons the cards use, so one thing never wears two faces on one screen.
   `calendar` is the one the insight cards have no equivalent for — a meeting is
   never an insight — and it borrows the tile's icon rather than inventing one. */
const HIGHLIGHT_META: Record<BriefHighlightSource, { Icon: typeof Inbox; label: string }> = {
  ...SOURCE_META,
  calendar: { Icon: CalendarDays, label: 'Takvim' },
};

/** Which section owns a named item, so clicking it can open the right list. */
const HIGHLIGHT_SECTION: Record<BriefHighlightSource, BriefSectionKey> = {
  gmail: 'inbox',
  jira: 'work',
  github: 'code',
  calendar: 'calendar',
};

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
  const [focusItemId, setFocusItemId] = useState<string | null>(null);
  const [playbooks, setPlaybooks] = useState<Playbook[]>([]);
  const [playbookPhase, setPlaybookPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [playbookError, setPlaybookError] = useState<string | null>(null);
  const [starting, setStarting] = useState<string | null>(null);
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

  /* The shelf is not part of the brief and must not wait for it: a slow
     provider should never be the reason a written-down flow cannot be
     started. Separate request, separate failure. */
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const rows = await getPlaybookSource().list();
        if (!alive) return;
        setPlaybooks(rows);
        setPlaybookPhase('ready');
      } catch (err) {
        if (!alive) return;
        setPlaybookError(err instanceof Error ? err.message : 'Hazır akışlar okunamadı.');
        setPlaybookPhase('error');
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const priority = useMemo(
    () => (brief?.priority ?? []).filter((c) => !dismissed.includes(c.id)),
    [brief?.priority, dismissed],
  );

  /*
    The digest orders the day and says why; the cards are keyed by the same
    item id (`gmail:…`, `jira:KAN-4`, `github-pr:owner/repo#12`), so the reason
    can ride along with the thing it is about. Plenty of cards will not have
    one — the model writes at most five and skips what it cannot justify — and
    those rows stay exactly as they are today. An empty slot or a dash would be
    a worse answer than no answer.
  */
  const whyById = useMemo(() => {
    const map = new Map<string, string>();
    for (const entry of brief?.digest?.priorities ?? []) {
      const why = entry.why?.trim();
      if (entry.itemId && why && !map.has(entry.itemId)) map.set(entry.itemId, why);
    }
    return map;
  }, [brief?.digest]);

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

  /* Same landing as a suggestion from a card: the run screen, streaming.
     The write step still stops at the approval gate — starting a flow from
     Bugün changes where it was started, not what it is allowed to do. */
  const startPlaybook = async (id: string) => {
    if (starting) return;
    setStarting(id);
    setPlaybookError(null);
    try {
      const { runId } = await getPlaybookSource().run(id);
      onNavigate('#/sohbet');
      await openRun(runId);
    } catch (err) {
      setPlaybookError(err instanceof Error ? err.message : 'Akış başlatılamadı.');
    } finally {
      setStarting(null);
    }
  };

  /*
    Where a named item takes you: its section panel, with the row marked.

    The alternative was the priority list above, and it loses. The tally names
    what ARRIVED; the priority list holds what the model chose to act on, and
    the two are not the same set — a meeting is never in it, and neither is the
    second mail on a busy morning. Sending half the chips to a row that is not
    there is worse than sending all of them to the list that, by construction,
    contains every one of them and links out to the real thing.
  */
  const openHighlight = (highlight: BriefHighlight) => {
    setOpenKey(HIGHLIGHT_SECTION[highlight.source] ?? 'inbox');
    setFocusItemId(highlight.itemId);
  };

  const toggleSection = (key: BriefSectionKey) => {
    setOpenKey((cur) => (cur === key ? null : key));
    // Opening a section by hand is a different intent — drop the old mark.
    setFocusItemId(null);
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
            ? `Brifing hazır. ${brief.today ? `${brief.today.headline} ` : ''}${priority.length} öncelikli kart.`
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

            {/*
              The counted day, directly under the date — the first sentence on
              the screen, and the only one that is there whether or not a model
              could write anything. It sits inside the header block on purpose:
              a section of its own would have cost the 24px body gap on top of
              its own height, and Bugün has to keep fitting one viewport.

              No box, no tinted panel. On a quiet day the headline says so in
              one line and there is nothing else to draw — framing zeros would
              be exactly the fake density the count exists to avoid.
            */}
            {brief?.today ? (
              <div className="tally">
                <p className="tally__headline">{brief.today.headline}</p>
                {brief.today.lines.length > 0 && (
                  <p className="tally__lines">
                    {brief.today.lines.map((line) => (
                      <span className="tally__line" key={line}>
                        {line}
                      </span>
                    ))}
                  </p>
                )}

                {/*
                  The counts say how much; these say what. Empty whenever only
                  mailings arrived — a list of nothing named is not worth a row,
                  and the count above already told that story honestly.
                */}
                {brief.today.highlights.length > 0 && (
                  <ul className="tally__named">
                    {brief.today.highlights.map((highlight) => {
                      const meta = HIGHLIGHT_META[highlight.source] ?? HIGHLIGHT_META.gmail;
                      return (
                        <li key={`${highlight.source}:${highlight.itemId}`}>
                          <button
                            type="button"
                            className="tally__item"
                            onClick={() => openHighlight(highlight)}
                          >
                            <span className="tally__item-icon" aria-hidden>
                              <meta.Icon size={13} />
                            </span>
                            <span className="tally__item-text">
                              <span className="tally__item-label">{highlight.label}</span>
                              {highlight.detail ? (
                                <span className="tally__item-detail">{highlight.detail}</span>
                              ) : null}
                            </span>
                            <span className="sr-only">
                              {meta.label} — bölüm listesinde göster
                            </span>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            ) : null}

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
            {/* ---------------- GÜNÜN ÖZETİ ---------------- */}
            {/* Absent whenever the model could not write it — an honest gap beats a
                sentence that sounds like a summary and says nothing. It rides UNDER
                the counted headline above and never replaces it: the count says how
                much is waiting, this says what it means. Two different claims, so
                they get two different voices and never sit on top of each other. */}
            {brief?.digest ? (
              <motion.section className="digest" aria-labelledby="digest-h" {...enterProps(0, reduce)}>
                <h2 className="sr-only" id="digest-h">
                  Günün özeti
                </h2>
                <p className="digest__summary">{brief.digest.summary}</p>
                {brief.digest.advice ? (
                  <p className="digest__advice">
                    <Sparkles size={13} aria-hidden /> {brief.digest.advice}
                  </p>
                ) : null}
              </motion.section>
            ) : null}

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
                  why={whyById.get(focus.id) ?? null}
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
                        why={whyById.get(card.id) ?? null}
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
                    onToggle={() => toggleSection(meta.key)}
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
                      focusItemId={focusItemId}
                      onClose={() => {
                        setOpenKey(null);
                        setFocusItemId(null);
                      }}
                      onGoToConnections={() => onNavigate('#/connections')}
                      onRetry={() => void load('refresh')}
                    />
                  </motion.div>
                )}
              </AnimatePresence>
            </section>

            {/* ---------------- HAZIR AKIŞLAR ---------------- */}
            {/* Last on the screen on purpose: everything above answers "what
                happened", this answers "what can I start". Issue #15. */}
            <PlaybookShelf
              playbooks={playbooks}
              loading={playbookPhase === 'loading'}
              error={playbookError}
              starting={starting}
              onRun={(id) => void startPlaybook(id)}
            />
          </div>
        )}
      </div>
    </div>
  );
}
