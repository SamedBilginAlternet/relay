import { ArrowLeft, Repeat2, Square, TriangleAlert } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { ChatPanel } from '../components/ChatPanel';
import { StatusPill } from '../components/StatusPill';
import { WorkflowPanel } from '../components/WorkflowPanel';
import { getRunSource } from '../data';
import { ApiError } from '../data/ApiRunSource';
import { applyEvent, mergeRun } from '../data/applyEvent';
import type { StreamStatus } from '../data/RunSource';
import { formatDateTime } from '../lib/format';
import { isTerminal, useRunStore } from '../store/runStore';
import type { StepEditError } from '../store/runStore';
import type { Run } from '../types/api';

type Props = { runId: string; onBack: () => void; onNavigate: (hash: string) => void };

export function RunDetailScreen({ runId, onBack, onNavigate }: Props) {
  const [run, setRun] = useState<Run | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expandedStepId, setExpandedStepId] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const openRun = useRunStore((s) => s.openRun);
  const [rejectingStepId, setRejectingStepId] = useState<string | null>(null);
  const [busyStepId, setBusyStepId] = useState<string | null>(null);
  const [editError, setEditError] = useState<StepEditError | null>(null);
  /** A decision the server never took. Shown above the gate, which stays where it was. */
  const [decideError, setDecideError] = useState<string | null>(null);
  const [streamStatus, setStreamStatus] = useState<StreamStatus | 'idle'>('idle');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const fresh = await getRunSource().getRun(runId);
      // Merged, not assigned: by the time this answer lands the stream below may already
      // have moved the run on, and replacing it wholesale would undo those frames.
      setRun((current) => (current && current.id === fresh.id ? mergeRun(current, fresh) : fresh));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Akış yüklenemedi.');
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    void load();
  }, [load]);

  /*
    A run that is still going is watched here too, not only on Sohbet.

    Without this the screen was a photograph: approving from Geçmiş fired the request,
    refetched once — catching the run mid-`running` — and then froze. Measured live on
    2026-08-01, the run failed on the server at 04:18:48 while this screen went on
    counting the step's duration up past 54 seconds and showing no error at all. A
    decision you cannot see the result of is barely a decision; a second write gate in
    the same run was simply unreachable without a manual refresh.

    Same client API the chat store uses — the stream replays and refetches on reconnect,
    so nothing here polls.
  */
  const watching = run != null && !isTerminal(run.status);
  useEffect(() => {
    if (!watching) return;
    const stop = getRunSource().streamRun(runId, {
      onStatus: setStreamStatus,
      onEvent: (event) =>
        setRun((current) => (current && current.id === runId ? applyEvent(current, event) : current)),
      onResync: (fresh) =>
        setRun((current) => (current && current.id === fresh.id ? mergeRun(current, fresh) : current)),
    });
    return stop;
  }, [runId, watching]);

  /**
   * Decide on a step from the history screen. The store drives the live chat panel; this
   * screen owns a run it fetched itself, so it talks to the source directly.
   *
   * <p>The refetch is a floor, not the mechanism: the stream above is what carries the run
   * the rest of the way.
   */
  const decide = useCallback(
    async (stepId: string, action: 'approve' | 'reject', params?: Record<string, unknown>,
           reason?: string) => {
      setBusyStepId(stepId);
      setEditError(null);
      setDecideError(null);
      try {
        const source = getRunSource();
        if (action === 'approve') {
          await source.approveStep(runId, stepId, params);
        } else {
          await source.rejectStep(runId, stepId, reason ?? '');
        }
        setRejectingStepId(null);
        await load();
      } catch (err) {
        // 400 is the tool's schema refusing an edit — that belongs under the box that
        // caused it, and the step is still at the gate. Anything else (a 502 mid-deploy,
        // a dropped line) is not a parameter problem and must not be dressed up as one.
        if (err instanceof ApiError && err.status === 400) {
          setEditError({ stepId, message: err.message, fields: err.fields });
        } else {
          setDecideError(err instanceof Error ? err.message : 'İşlem başarısız.');
        }
      } finally {
        setBusyStepId(null);
      }
    },
    [runId, load],
  );

  /**
   * Stopping is honest about what it can undo: a tool call already sent to the provider
   * runs to its end, so the button promises "no further step", never "nothing happened".
   */
  const cancel = async () => {
    setCancelling(true);
    setError(null);
    setNotice(null);
    try {
      const stopped = await getRunSource().cancelRun(runId);
      setRun(stopped);
      if (!isTerminal(stopped.status)) {
        setNotice(
          'Durduruluyor — başlamış araç çağrısı yarıda kesilmiyor. O adım bitince akış kapanır; ' +
            'sonraki adımlar çalıştırılmayacak.',
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Akış durdurulamadı.');
    } finally {
      setCancelling(false);
    }
  };

  const rerun = async () => {
    try {
      const res = await getRunSource().rerun(runId);
      onNavigate('#/sohbet');
      await openRun(res.runId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Akış tekrar başlatılamadı.');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: '1 1 auto' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '10px 16px',
          borderBottom: '1px solid var(--border)',
          flexWrap: 'wrap',
        }}
      >
        <button type="button" className="btn btn--ghost btn--sm" onClick={onBack}>
          <ArrowLeft size={15} aria-hidden />
          Geçmiş
        </button>
        <div style={{ minWidth: 0, flex: '1 1 260px' }}>
          <p className="t-caption">Denetim izi · {formatDateTime(run?.createdAt ?? null)}</p>
          <h1
            style={{
              fontSize: 15,
              fontWeight: 500,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {run?.goal ?? (loading ? 'Yükleniyor…' : 'Akış')}
          </h1>
        </div>
        {run && <StatusPill status={run.status} />}
        {run && !isTerminal(run.status) && (
          <button
            type="button"
            className="btn btn--danger-outline btn--sm"
            disabled={cancelling}
            onClick={() => void cancel()}
          >
            <Square size={14} aria-hidden />
            {cancelling ? 'Durduruluyor…' : 'Durdur'}
          </button>
        )}
        {run && (
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void rerun()}>
            <Repeat2 size={14} aria-hidden />
            Tekrar çalıştır
          </button>
        )}
      </div>

      {notice && (
        <div style={{ padding: '12px 16px 0' }}>
          <div className="notice">{notice}</div>
        </div>
      )}

      {decideError && (
        <div style={{ padding: '12px 16px 0' }}>
          <div className="notice notice--danger" role="alert">
            <TriangleAlert size={16} aria-hidden />
            <span>{decideError}</span>
          </div>
        </div>
      )}

      {error && (
        <div style={{ padding: 16 }}>
          <div className="notice notice--danger">
            <TriangleAlert size={16} aria-hidden />
            <span>{error}</span>
          </div>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            style={{ marginTop: 12 }}
            onClick={() => void load()}
          >
            Tekrar dene
          </button>
        </div>
      )}

      {!error && (
        <div className="workbench workbench--stack">
          <ChatPanel
            run={run}
            phase={loading ? 'loading' : 'ready'}
            error={null}
            readOnly
          />
          {/*
            A finished run is history and reads as history. A run that is still waiting on
            a human is not: this is the screen someone lands on from Geçmiş, and the trail
            on it says "akış panelinde Onayla veya Reddet" — so the buttons have to be here,
            or the sentence is a dead end.
          */}
          <WorkflowPanel
            run={run}
            phase={loading ? 'loading' : 'ready'}
            error={null}
            streamStatus={streamStatus}
            expandedStepId={expandedStepId}
            readOnly={run == null || isTerminal(run.status)}
            rejectingStepId={rejectingStepId}
            busyStepId={busyStepId}
            editError={editError}
            onToggleStep={(id) => setExpandedStepId((cur) => (cur === id ? null : id))}
            onApprove={(id, params) => void decide(id, 'approve', params)}
            onReject={(id, reason) => void decide(id, 'reject', undefined, reason)}
            onStartReject={setRejectingStepId}
            onRetry={() => void load()}
          />
        </div>
      )}
    </div>
  );
}
