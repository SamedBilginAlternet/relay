import { ArrowLeft, Repeat2, TriangleAlert } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { ChatPanel } from '../components/ChatPanel';
import { StatusPill } from '../components/StatusPill';
import { WorkflowPanel } from '../components/WorkflowPanel';
import { getRunSource } from '../data';
import { formatDateTime } from '../lib/format';
import { useRunStore } from '../store/runStore';
import type { Run } from '../types/api';

type Props = { runId: string; onBack: () => void; onNavigate: (hash: string) => void };

export function RunDetailScreen({ runId, onBack, onNavigate }: Props) {
  const [run, setRun] = useState<Run | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expandedStepId, setExpandedStepId] = useState<string | null>(null);
  const openRun = useRunStore((s) => s.openRun);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRun(await getRunSource().getRun(runId));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Akış yüklenemedi.');
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    void load();
  }, [load]);

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
        {run && (
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void rerun()}>
            <Repeat2 size={14} aria-hidden />
            Tekrar çalıştır
          </button>
        )}
      </div>

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
          <WorkflowPanel
            run={run}
            phase={loading ? 'loading' : 'ready'}
            error={null}
            streamStatus="closed"
            expandedStepId={expandedStepId}
            readOnly
            onToggleStep={(id) => setExpandedStepId((cur) => (cur === id ? null : id))}
            onRetry={() => void load()}
          />
        </div>
      )}
    </div>
  );
}
