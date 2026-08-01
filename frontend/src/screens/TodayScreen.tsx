import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  ChevronDown,
  ClipboardList,
  GitPullRequest,
  Inbox,
  ListChecks,
  Plug,
  RefreshCw,
  Undo2,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActionRow, GapRow, MeetingRow } from '../components/ActionFeed';
import { PlaybookShelf, SectionPanel, SectionTile } from '../components/BriefSections';
import type { SectionMeta } from '../components/BriefSections';
import { describeLoadError, LoadError } from '../components/LoadError';
import { getBriefSource, RUN_SOURCE_KIND } from '../data';
import { ApiError } from '../data/ApiRunSource';
import { getPlaybookSource } from '../data/PlaybookSource';
import type { Playbook } from '../data/PlaybookSource';
import { formatDayMonth } from '../lib/format';
import { enterProps, expandProps } from '../lib/motion';
import { EMPTY_SECTION } from '../types/brief';
import type { Brief, BriefSectionKey, InsightCard, SuggestedAction } from '../types/brief';

type Props = { onNavigate: (hash: string) => void };

type Phase = 'loading' | 'refreshing' | 'ready' | 'error';

/**
 * Could pressing the same button again help?
 *
 * Not the same question as "what went wrong" — that one belongs to
 * {@link describeLoadError}, which every screen shares. This one does not:
 * whether a retry is worth offering depends on the failure this screen's own
 * data source raises. A dropped connection or a 500 is worth retrying, nothing
 * about the request was wrong. A 4xx is the server having read the request and
 * said no; the same request will be refused the same way, and offering "Tekrar
 * dene" for it is a button that cannot work.
 */
function canRetry(err: unknown): boolean {
  return err instanceof ApiError ? err.status < 400 || err.status >= 500 : true;
}

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

/**
 * The day in one sentence, counted off the rows the user can see.
 *
 * "tanesi" rather than a suffix, the same choice the server makes: Turkish
 * vowel harmony would need 2'si, 3'ü, 6'sı… and a wrong suffix reads as sloppy.
 */
function dayHeadline(rows: number, urgent: number): string {
  if (rows === 0) return 'Bugün seni bekleyen bir şey görünmüyor.';
  const sentence = `Bugün ${rows} iş seni bekliyor`;
  return urgent > 0 ? `${sentence}, ${urgent} tanesi acil.` : `${sentence}.`;
}

/** Where the header badge went (issue: "canlı api yazısını sil"): the data
 *  source is a property of *the brief*, not of the whole product chrome. */
const SOURCE_NOTE =
  RUN_SOURCE_KIND === 'api' ? 'Canlı API' : 'Demo veri (senaryo)';

export function TodayScreen({ onNavigate }: Props) {
  const [brief, setBrief] = useState<Brief | null>(null);
  const [phase, setPhase] = useState<Phase>('loading');
  /* The raw failure, not a sentence: turning one into the other is
     `describeLoadError`'s job and every screen uses the same one. */
  const [error, setError] = useState<unknown>(null);
  const [dismissed, setDismissed] = useState<string[]>([]);
  const [busy, setBusy] = useState<{ cardId: string; tool: string } | null>(null);
  const [openKey, setOpenKey] = useState<BriefSectionKey | null>(null);
  /* The four-section grid, closed until asked for. It answers "where did this
     come from", which is a question about the plumbing — the screen's own
     question is "what do I do now", and that is the feed above. */
  const [sectionsOpen, setSectionsOpen] = useState(false);
  const [playbooks, setPlaybooks] = useState<Playbook[]>([]);
  const [playbookPhase, setPlaybookPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [playbookError, setPlaybookError] = useState<string | null>(null);
  const [starting, setStarting] = useState<string | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);
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
      setError(err);
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

    Having a reason is not the same as being worth printing: the row decides
    that for itself, against its own title, in `reasonEarnsItsLine`. This map
    only carries the sentence to the row that can judge it.
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

  /*
    The one number on this screen.

    There used to be three, none of which explained the others: the headline
    said 9 (everything that arrived today), the section label said 5 (what the
    model chose to act on) and the drawer said 19 (rows across all four source
    lists). The product's whole claim is "gözünün önünde", and a screen that
    cannot answer "kaç işim var" out loud does not make it.

    The contract is now: the number IS the list. Every row in the feed counts
    once — the meeting, each job, each missing connection — because every one
    of them is something the user has to deal with today, and every one of them
    carries the matching ordinal. The server's own `today.headline` counts
    arrivals instead, so it is no longer shown; that number's honest home is the
    "Tümünü gör" line, which says how many records were read.
  */
  const rowCount = (meetingRow ? 1 : 0) + priority.length + gaps.length;
  const urgentCount = priority.filter((card) => card.urgency === 'high').length;
  const headline = dayHeadline(rowCount, urgentCount);
  /* Trimmed, because a model asked for three sentences occasionally writes six
     and the list has to stay above the fold. */
  const summary = (brief?.digest?.summary ?? '').trim();
  /* Counted by the server, never derived here: these are the numbers that
     cannot be wrong, and recomputing them in the client is how they start
     disagreeing with each other. */
  const dayLines = brief?.today?.lines ?? [];
  const advice = (brief?.digest?.advice ?? '').trim();

  /*
    Which row gets the screen's one filled button (issue #78).

    The list is already ordered, and the numbers already say "start at the top";
    the accent is the same instruction said with weight instead of words, so it
    goes to the topmost row that actually has something to press. Rows above it
    without an action — a meeting with no runnable prep flow — are skipped
    rather than swallowing the emphasis nobody can act on.
  */
  const primaryRow = useMemo(() => {
    let row = 0;
    if (meetingRow) {
      if (canPrepare) return row;
      row += 1;
    }
    for (const card of priority) {
      if (card.suggestedActions.length > 0) return row;
      row += 1;
    }
    // Every gap row has a button, so the first of them is the fallback; when
    // there are none either, no row matches and nothing is filled.
    return gaps.length > 0 ? row : -1;
  }, [meetingRow, canPrepare, priority, gaps.length]);

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
      // Same engine as always: the run view, named in the address bar so it can
      // be found again, loads it and lets it stream.
      onNavigate(`#/sohbet/${runId}`);
    } catch (err) {
      setError(err);
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
      onNavigate(`#/sohbet/${runId}`);
    } catch (err) {
      setPlaybookError(err instanceof Error ? err.message : 'Akış başlatılamadı.');
    } finally {
      setStarting(null);
    }
  };

  const toggleSection = (key: BriefSectionKey) => {
    setOpenKey((cur) => (cur === key ? null : key));
  };

  const toggleSections = () => {
    setSectionsOpen((cur) => {
      if (cur) {
        // Closing the drawer closes what was open inside it; reopening to a
        // panel the user cannot remember choosing is its own small betrayal.
        setOpenKey(null);
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
          ? describeLoadError(error)
          : brief
            ? `Brifing hazır. ${headline}`
            : '';

  return (
    <div className="page">
      {/* `--app` as well as `brief`: both set the same 1040px, but only the modifier
          brings `scrollbar-gutter: stable` (screens.css). Without it Bugün sat 5px
          left of every other screen, and the nav wobbled on each click. */}
      <div className="page__inner page__inner--app brief">
        <motion.div className="brief-top" {...enterProps(0, reduce)}>
          <div className="brief-top__text">
            <h1 className="t-title">
              Bugün <span className="brief-top__dot" aria-hidden>·</span>{' '}
              <span className="brief-top__date">{formatDayMonth(brief?.date ?? null)}</span>
            </h1>

            {/*
              One sentence, directly under the date, and then the list. What
              used to sit here — a counted breakdown line, four "öne çıkan"
              chips and a written paragraph — named the same five records the
              list below names, so the first action started after 53% of the
              screen. Nothing was lost by deleting them: the chips linked to
              rows that are already visible, the breakdown is on the "Tümünü
              gör" line, and the reason a job is first rides on that job's row.
            */}
            {!loading && brief != null ? (
              <p className="tally__headline">{headline}</p>
            ) : null}

            {/*
              And then the day in words. The counted line above cannot be wrong
              — it is arithmetic over the rows on screen — but it also cannot
              say *what* the day is about, and "6 iş seni bekliyor" is not a
              briefing. This sentence names the two or three things that matter,
              which is the whole reason someone opens this screen first.

              One paragraph, never the advice line under it: "önce şunu, sonra
              bunu" is what the numbered list below already says by being
              ordered. And nothing at all when the model could not write it —
              the counted line still stands on its own, so a spent token budget
              costs a sentence, not the screen.
            */}
            {!loading && summary ? <p className="tally__summary">{summary}</p> : null}

            {/*
              What actually arrived, counted by the server. This is a different
              question from the headline above it and has to look like one: the
              headline counts what needs doing (the rows below), this counts
              what came in — fifteen mails of which eight were mailings and
              seven from people. #60 was opened because three numbers sat on
              this screen with nothing saying what each was; the label is what
              was missing, not the numbers.
            */}
            {!loading && dayLines.length > 0 ? (
              <p className="tally__lines">
                <span className="tally__lines-label">Bugün gelenler</span>
                {dayLines.join(' · ')}
              </p>
            ) : null}

            {/* And the one thing to do first, when the model committed to one. */}
            {!loading && advice ? (
              <p className="tally__advice">
                <ArrowRight size={13} aria-hidden />
                <span>{advice}</span>
              </p>
            ) : null}

            {/*
              The dot and the source, and nothing else. "Öneriye basmadan hiçbir şey
              çalışmaz" used to hang off the end — a promise printed where a status
              belongs. The promise is kept by the gate, and now stated where it is
              about to matter: on the row's own draft, next to the values that would
              be sent. A line that reassures on every screen stops being read on any.
            */}
            <p className="brief-top__meta">
              <span
                className={`src-dot src-dot--${RUN_SOURCE_KIND}${phase === 'error' ? ' src-dot--down' : ''}`}
                aria-hidden
              />
              {phase === 'error' ? `${SOURCE_NOTE} — yanıt yok` : SOURCE_NOTE}
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

        {/* The same box the other five screens draw. No retry for a refused
            request: the same call gets the same answer, and a button that
            cannot work is worse than no button. */}
        {phase === 'error' && (
          <>
            <LoadError
              error={error}
              onRetry={canRetry(error) ? () => void load('initial') : undefined}
            />
            {/*
              A build-time flag, not a runtime check: `import.meta.env.DEV` is
              false in the production bundle, so this line and the environment
              variable in it are compiled out. It used to ship, and it told a
              user of the live product to go and set VITE_RUN_SOURCE — an
              instruction they cannot follow, about a thing they should never
              have to know exists. It sits outside the box for the same reason:
              the box is what a user reads, and this is a note to a developer.
            */}
            {import.meta.env.DEV && (
              <p className="t-caption">
                Backend ayakta değilse <code className="t-mono">VITE_RUN_SOURCE=mock</code> ile
                demo verisiyle çalışabilirsin.
              </p>
            )}
          </>
        )}

        {/* A brief that loaded and an action that would not start are different
            failures: this one leaves the screen usable, so it says what happened
            and stops. The button to try again is the row's own. */}
        {phase !== 'error' && error != null && <LoadError error={error} />}

        {/*
          If the brief never arrived there is nothing honest to show: empty
          sections would read as "nothing to do today", which is a lie.
          The error card above is the whole screen until it loads.
        */}
        {!showBody ? null : (
          <div className="brief-body">
            {/* The written summary used to sit here, between the headline and the
                list, and it said the list out loud a second time: the paragraph
                named the same records, the advice line under it repeated the
                first row's reason word for word. The digest is still fetched and
                still used — `digest.priorities[].why` is what each row says under
                its title, which is the one place a reason can be read next to the
                thing it is about. What is gone is the copy of it up here. */}

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
                {!loading && rowCount > 0 && <span className="t-caption">{rowCount} iş</span>}
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

              {/* Nothing in the feed at all — not "no cards, but a meeting and
                  two broken connections", which is what `priority.length` alone
                  used to mean here while the list underneath was not empty. */}
              {!loading && rowCount === 0 && (
                <p className="brief-prio__clear">
                  <CheckCircle2 size={16} aria-hidden />
                  {dismissed.length > 0
                    ? 'Yapılacak liste temizlendi.'
                    : 'Bekleyen bir iş yok — bugün acil işaretlenen mail, PR ya da kayıt bulunmadı.'}
                </p>
              )}

              {!loading && rowCount > 0 && (
                <ul className="arow-list">
                  <AnimatePresence initial={false}>
                    {meetingRow && (
                      <MeetingRow
                        key="meeting"
                        index={0}
                        primary={primaryRow === 0}
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
                        primary={primaryRow === (meetingRow ? i + 1 : i)}
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
                        primary={primaryRow === priority.length + i + (meetingRow ? 1 : 0)}
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
                  {/* The second counter on the screen, so it has to say what it
                      counts: these are the rows that were READ, not the jobs
                      that came out of them. */}
                  Gelen kutusu, üstümdeki işler, kod, takvim
                  {!loading && sectionTotal > 0 ? ` · ${sectionTotal} kayıt tarandı` : ''}
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
                              onClose={() => setOpenKey(null)}
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
