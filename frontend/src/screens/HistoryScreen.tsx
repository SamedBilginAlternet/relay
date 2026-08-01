import { ArrowRight, History, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { StatusPill } from '../components/StatusPill';
import { getRunSource } from '../data';
import { formatRelative, formatTokens, formatUsd } from '../lib/format';
import type { RunSummary } from '../types/api';
import '../styles/screens.css';

type Props = { onOpen: (runId: string) => void };

export function HistoryScreen({ onOpen }: Props) {
  const [rows, setRows] = useState<RunSummary[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await getRunSource().listRuns());
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Geçmiş</h1>
            <p className="t-caption">
              Çalışmış akışlar. Birine tıkla — adımlar, parametreler ve ajan mesajlarıyla tam
              denetim izi açılır.
            </p>
          </div>
          <button type="button" className="btn btn--outline btn--sm" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        {error != null && <LoadError error={error} onRetry={() => void load()} />}

        {loading && !rows && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div className="skeleton" style={{ height: 84 }} />
            <div className="skeleton" style={{ height: 84, opacity: 0.7 }} />
            <div className="skeleton" style={{ height: 84, opacity: 0.4 }} />
          </div>
        )}

        {!loading && rows && rows.length === 0 && (
          <EmptyState
            Icon={History}
            title="Henüz çalışmış akış yok"
            description="İlk işini sohbet ekranından ver; biten her akış buraya denetim iziyle düşer."
          />
        )}

        {rows && rows.length > 0 && (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {rows.map((r) => (
              <li key={r.id}>
                <button type="button" className="run-card" onClick={() => onOpen(r.id)}>
                  <div className="run-card__main">
                    <span className="run-card__goal">{r.goal}</span>
                    <span className="run-card__meta">
                      <span>{formatRelative(r.createdAt)}</span>
                      <span>{r.stepCount} adım</span>
                      <span>{formatTokens(r.costTokens)} token</span>
                      <span>{formatUsd(r.costUsd)}</span>
                    </span>
                  </div>
                  <StatusPill status={r.status} />
                  <ArrowRight size={16} aria-hidden style={{ color: 'var(--fg-muted)' }} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
