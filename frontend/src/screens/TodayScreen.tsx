import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
  CalendarDays,
  CheckCircle2,
  ChevronDown,
  ClipboardList,
  GitPullRequest,
  Inbox,
  ListChecks,
  Plug,
  RefreshCw,
  Sparkles,
  TriangleAlert,
  Undo2,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActionRow, GapRow, MeetingRow } from '../components/ActionFeed';
import { PlaybookShelf, SectionPanel, SectionTile } from '../components/BriefSections';
import type { SectionMeta } from '../components/BriefSections';
import { getBriefSource, RUN_SOURCE_KIND } from '../data';
import { getPlaybookSource } from '../data/PlaybookSource';
import type { Playbook } from '../data/PlaybookSource';
import { formatDayMonth } from '../lib/format';
import { SOURCE_META } from '../lib/insight';
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

/** The written-down flow that reads a meeting and finds what it is about. */
const MEETING_PREP_ID = 'toplanti-hazirligi';

/**
 * "14:00" → 840 minutes past midnight; anything else → null.
 *
 * The brief gives a meeting a start time in the user's own zone and nothing
 * else — no end, no duration. That is enough to tell a meeting that has not
 * started from one that has, and not enough for anything cleverer, so nothing
 * cleverer is attempted here. An unreadable clock returns null and the meeting
 * is treated as still ahead: guessing it is over would delete it from the day.
 */
function minutesOfDay(meta?: string | null): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec((meta ?? '').trim());
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

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
  /* The four-section grid, closed until asked for. It answers "where did this
     come from", which is a question about the plumbing — the screen's own
     question is "what do I do now", and that is the feed above. */
  const [sectionsOpen, setSectionsOpen] = useState(false);
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
      // Two 200ms expansions can be in flight (the drawer, then the panel
      // inside it), so this waits out both rather than measuring a row that
      // has not stopped moving.
    }, 420);
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
    A section that could not be fetched is work too — the kind that has to be
    done before any of the rest can be. It used to live as a card inside the
    grid; with the grid closed by default that would have hidden the one thing
    a half-configured account most needs to see, so it rides in the feed.
  */
  const gaps = useMemo(
    () =>
      SECTIONS.map((meta) => ({ meta, section: brief?.[meta.key] ?? EMPTY })).filter(
        (entry) => entry.section.status !== 'ok',
      ),
    [brief],
  );

  /*
    The next meeting that has not started yet — one row, never a list.

    The prep flow reads *today's* meeting itself; a row per event would fire the
    same run three times over and say three different things about which one it
    was. One row, named, is the honest amount.
  */
  const nextMeeting = useMemo(() => {
    const section = brief?.calendar;
    if (!section || section.status !== 'ok') return null;
    const now = new Date();
    const nowMinutes = now.getHours() * 60 + now.getMinutes();
    return (
      section.items
        .map((item) => ({ item, at: minutesOfDay(item.meta) }))
        .filter((entry) => entry.at == null || entry.at >= nowMinutes)
        .sort((a, b) => (a.at ?? Number.MAX_SAFE_INTEGER) - (b.at ?? Number.MAX_SAFE_INTEGER))[0] ??
      null
    );
  }, [brief?.calendar]);

  /* No flow, no button: a meeting row that promises preparation it cannot run
     is worse than a meeting row that only says a meeting is coming. */
  const meetingPrep = playbooks.find((p) => p.id === MEETING_PREP_ID) ?? null;
  const canPrepare = meetingPrep != null && meetingPrep.runnable;
  const meetingRow = nextMeeting
    ? {
        title: nextMeeting.item.title,
        detail: [
          [nextMeeting.item.meta, nextMeeting.item.subtitle].filter(Boolean).join(' · '),
          'başlamadan önce ilgili kayıtlar ve mailler toplansın',
        ]
          .filter(Boolean)
          .join(' — '),
      }
    : null;

  /* The shelf is the catalogue; the row above is the same flow at the moment it
     is worth running. Showing both would be the same button twice. */
  const shelfPlaybooks = useMemo(
    () =>
      meetingRow && canPrepare
        ? playbooks.filter((p) => p.id !== MEETING_PREP_ID)
        : playbooks,
    [playbooks, meetingRow, canPrepare],
  );

  /** How much is behind the closed grid, so the disclosure is not a mystery box. */
  const sectionTotal = useMemo(
    () =>
      SECTIONS.reduce((sum, meta) => {
        const section = brief?.[meta.key] ?? EMPTY;
        return sum + (section.status === 'ok' ? section.items.length : 0);
      }, 0),
    [brief],
  );

  const runAction = async (card: InsightCard, action: SuggestedAction) => {
    setBusy({ cardId: card.id, tool: action.tool });
    setError(null);
    try {
      // The whole card, not its id: the flow has to know what it is about before it
      // starts — an agent handed only "Cevap yaz" wrote a draft titled "Re: Cevap".
      const { runId } = await getBriefSource().startFromSuggestion(card, action);
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
    // The lists live behind a disclosure now; naming a row has to open it too,
    // or the click lands on something that is not on the screen.
    setSectionsOpen(true);
    setOpenKey(HIGHLIGHT_SECTION[highlight.source] ?? 'inbox');
    setFocusItemId(highlight.itemId);
  };

  const toggleSection = (key: BriefSectionKey) => {
    setOpenKey((cur) => (cur === key ? null : key));
    // Opening a section by hand is a different intent — drop the old mark.
    setFocusItemId(null);
  };

  const toggleSections = () => {
    setSectionsOpen((cur) => {
      if (cur) {
        // Closing the drawer closes what was open inside it; reopening to a
        // panel the user cannot remember choosing is its own small betrayal.
        setOpenKey(null);
        setFocusItemId(null);
      }
      return !cur;
    });
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
            ? `Brifing hazır. ${brief.today ? `${brief.today.headline} ` : ''}${priority.length} yapılacak iş.`
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

            {/* ---------------- YAPILACAK İŞLER ---------------- */}
            {/* The screen's spine (issue #30). One row per job — what it is, why
                now, and the action itself, already on the screen. Where it came
                from is a badge on the row; it was a section heading until today,
                which answered a question nobody had asked. */}
            <section className="brief-prio" aria-labelledby="brief-priority-h">
              <div className="brief-prio__head">
                <h2 className="t-label" id="brief-priority-h">
                  <ListChecks size={12} aria-hidden /> Yapılacak işler
                </h2>
                {!loading && priority.length > 0 && (
                  <span className="t-caption">{priority.length} iş</span>
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
                  <div className="skeleton" style={{ height: 68 }} />
                  <div className="skeleton" style={{ height: 68, opacity: 0.6 }} />
                  <div className="skeleton" style={{ height: 68, opacity: 0.4 }} />
                </div>
              )}

              {!loading && priority.length === 0 && (
                <p className="brief-prio__clear">
                  <CheckCircle2 size={16} aria-hidden />
                  {dismissed.length > 0
                    ? 'Yapılacak liste temizlendi.'
                    : 'Bekleyen bir iş yok — bugün acil işaretlenen mail, PR ya da kayıt bulunmadı.'}
                </p>
              )}

              {!loading && (priority.length > 0 || gaps.length > 0 || meetingRow != null) && (
                <ul className="arow-list">
                  <AnimatePresence initial={false}>
                    {meetingRow && (
                      <MeetingRow
                        key="meeting"
                        index={0}
                        title={meetingRow.title}
                        detail={meetingRow.detail}
                        busy={starting === MEETING_PREP_ID}
                        onPrepare={
                          canPrepare ? () => void startPlaybook(MEETING_PREP_ID) : null
                        }
                      />
                    )}
                    {priority.map((card, i) => (
                      <ActionRow
                        key={card.id}
                        card={card}
                        index={meetingRow ? i + 1 : i}
                        rank={i + 1}
                        why={whyById.get(card.id) ?? null}
                        busyTool={busy?.cardId === card.id ? busy.tool : null}
                        onAction={(c, a) => void runAction(c, a)}
                        onDismiss={(id) => setDismissed((cur) => [...cur, id])}
                      />
                    ))}
                    {gaps.map((gap, i) => (
                      <GapRow
                        key={`gap-${gap.meta.key}`}
                        index={priority.length + i + (meetingRow ? 1 : 0)}
                        status={gap.section.status === 'error' ? 'error' : 'unavailable'}
                        provider={gap.meta.connectLabel}
                        scope={gap.meta.title}
                        reason={gap.section.reason}
                        onAction={() =>
                          gap.section.status === 'error'
                            ? void load('refresh')
                            : onNavigate('#/connections')
                        }
                      />
                    ))}
                  </AnimatePresence>
                </ul>
              )}
            </section>

            {/* ---------------- Kaynak listeleri (ikincil) ---------------- */}
            {/* Closed by default and deliberately not deleted: partial success,
                the `unavailable`/`error` states and the full lists all hang off
                it. The gaps themselves are promoted into the feed above, so a
                missing integration is visible with this drawer shut. */}
            <section className="secs" aria-labelledby="secs-h">
              <h2 className="sr-only" id="secs-h">
                Kaynak listeleri
              </h2>
              <button
                type="button"
                className={`secs__toggle${sectionsOpen ? ' secs__toggle--open' : ''}`}
                aria-expanded={sectionsOpen}
                aria-controls="secs-body"
                onClick={toggleSections}
              >
                <ChevronDown size={16} aria-hidden className="secs__chev" />
                <span className="secs__label">
                  {sectionsOpen ? 'Listeleri gizle' : 'Tümünü gör'}
                </span>
                <span className="secs__sub">
                  Gelen kutusu, üstümdeki işler, kod, takvim
                  {!loading && sectionTotal > 0 ? ` · ${sectionTotal} kayıt` : ''}
                </span>
                {!loading && gaps.length > 0 && (
                  <span className="secs__warn">
                    <Plug size={12} aria-hidden />
                    {gaps.length} bağlantı eksik
                  </span>
                )}
              </button>

              <AnimatePresence initial={false}>
                {sectionsOpen && (
                  <motion.div key="secs" {...expandProps(reduce)}>
                    <div className="secs__body" id="secs-body">
                      <div className="tile-strip">
                        {SECTIONS.map((meta, i) => (
                          <SectionTile
                            key={meta.key}
                            meta={meta}
                            index={i}
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
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </section>

            {/* ---------------- HAZIR AKIŞLAR ---------------- */}
            {/* Last on the screen on purpose: everything above answers "what
                happened", this answers "what can I start". Issue #15. */}
            <PlaybookShelf
              playbooks={shelfPlaybooks}
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
