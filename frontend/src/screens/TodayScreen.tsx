import { AnimatePresence } from 'motion/react';
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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { BriefSectionCard } from '../components/BriefSectionCard';
import { EmptyState } from '../components/EmptyState';
import { InsightCardView } from '../components/InsightCardView';
import { getBriefSource } from '../data';
import { formatDayMonth } from '../lib/format';
import { useRunStore } from '../store/runStore';
import type { Brief, InsightCard, SuggestedAction } from '../types/brief';

type Props = { onNavigate: (hash: string) => void };

type Phase = 'loading' | 'refreshing' | 'ready' | 'error';

const SECTIONS = [
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
] as const;

export function TodayScreen({ onNavigate }: Props) {
  const [brief, setBrief] = useState<Brief | null>(null);
  const [phase, setPhase] = useState<Phase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string[]>([]);
  const [busy, setBusy] = useState<{ cardId: string; tool: string } | null>(null);
  const openRun = useRunStore((s) => s.openRun);

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
      <div className="page__inner">
        <div className="page__head brief-head">
          <div className="page__head-text">
            <h1 className="t-title">
              Bugün <span className="brief-head__dot" aria-hidden>·</span>{' '}
              <span className="brief-head__date">{formatDayMonth(brief?.date ?? null)}</span>
            </h1>
            <p className="t-caption">
              Seni bekleyen işler tek ekranda. Bir öneriye bas — normal bir Relay akışı başlar,
              yazma adımı yine onayını ister.
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
        </div>

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
        <>
        {/* ---------------- ÖNCELİKLİ ---------------- */}
        <section className="brief-priority" aria-labelledby="brief-priority-h">
          <div className="brief-priority__head">
            <h2 className="t-label" id="brief-priority-h">
              <Sparkles size={12} aria-hidden /> Öncelikli
            </h2>
            {/* The "suggestion ≠ execution" rule (BRIEF.md §3) belongs here once.
                Repeating it under every card turned the promise into wallpaper. */}
            <span className="t-caption">
              AI katmanının öne çıkardıkları — öneri, eylem değil: tıklanmadan hiçbir şey
              çalışmaz.
            </span>
          </div>

          {loading && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div className="skeleton" style={{ height: 148 }} />
              <div className="skeleton" style={{ height: 148, opacity: 0.6 }} />
            </div>
          )}

          {!loading && priority.length > 0 && (
            <div className="insight-stack">
              <AnimatePresence initial={false}>
                {priority.map((card, i) => (
                  <InsightCardView
                    key={card.id}
                    card={card}
                    index={i}
                    busyTool={busy?.cardId === card.id ? busy.tool : null}
                    onAction={(c, a) => void runAction(c, a)}
                    onDismiss={(id) => setDismissed((cur) => [...cur, id])}
                  />
                ))}
              </AnimatePresence>
            </div>
          )}

          {!loading && priority.length === 0 && dismissed.length === 0 && (
            <EmptyState
              Icon={CheckCircle2}
              title="Öne çıkan bir şey yok"
              description="Bugün acil işaretlenen mail, PR ya da kayıt bulunmadı. Aşağıdaki bölümlerde yine de her şey duruyor."
            />
          )}

          {!loading && dismissed.length > 0 && (
            <div className="brief-dismissed">
              <span className="t-caption">{dismissed.length} kart yoksayıldı</span>
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => setDismissed([])}>
                <Undo2 size={14} aria-hidden />
                Geri al
              </button>
            </div>
          )}
        </section>

        {/* ---------------- Compact sections ---------------- */}
        <div className="brief-grid">
          {SECTIONS.map((meta) => (
            <BriefSectionCard
              key={meta.key}
              title={meta.title}
              Icon={meta.Icon}
              connectLabel={meta.connectLabel}
              emptyText={meta.emptyText}
              loading={loading}
              section={brief?.[meta.key] ?? { status: 'ok', items: [] }}
              onGoToConnections={() => onNavigate('#/connections')}
              onRetry={() => void load('refresh')}
            />
          ))}
        </div>
        </>
        )}
      </div>
    </div>
  );
}
