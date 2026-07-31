import { Coins, Radio, RefreshCw, Wallet, WifiOff } from 'lucide-react';
import type { StreamStatus } from '../data/RunSource';
import { formatTokens, formatUsd } from '../lib/format';
import type { Run } from '../types/api';

type Props = {
  run: Run | null;
  streamStatus: StreamStatus | 'idle';
  showStream?: boolean;
};

/** Always visible at the top of the workflow panel. Fed live by SSE `run.cost`. */
export function CostBar({ run, streamStatus, showStream = true }: Props) {
  const tokens = run?.costTokens ?? 0;
  const usd = run?.costUsd ?? 0;
  const budget = run?.budgetUsd ?? null;
  const ratio = budget && budget > 0 ? Math.min(usd / budget, 1) : 0;
  const level = ratio >= 1 ? 'danger' : ratio >= 0.8 ? 'warn' : 'ok';

  return (
    <div className="cost-bar" role="group" aria-label="Akış maliyeti">
      <div className="cost-metric">
        <span className="t-label">
          <Coins size={11} aria-hidden style={{ marginRight: 4 }} />
          Token
        </span>
        <span className="cost-metric__value">{formatTokens(tokens)}</span>
      </div>

      <div className="cost-metric">
        <span className="t-label">Tahmini ücret</span>
        <span
          className={`cost-metric__value ${level === 'danger' ? 'cost-metric__value--danger' : level === 'warn' ? 'cost-metric__value--warn' : ''}`}
        >
          {formatUsd(usd)}
        </span>
      </div>

      {budget != null ? (
        <div className="budget">
          <span className="t-label">
            <Wallet size={11} aria-hidden style={{ marginRight: 4 }} />
            Bütçe {formatUsd(budget)}
          </span>
          <div
            className="budget__track"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(ratio * 100)}
            aria-label={`Bütçenin %${Math.round(ratio * 100)}'i kullanıldı`}
          >
            <div
              className={`budget__fill ${level === 'danger' ? 'budget__fill--danger' : level === 'warn' ? 'budget__fill--warn' : ''}`}
              style={{ width: `${Math.max(ratio * 100, 2)}%` }}
            />
          </div>
          <span className="t-caption">
            {level === 'danger'
              ? 'Bütçe doldu — ajan duracak ve soracak.'
              : `%${Math.round(ratio * 100)} kullanıldı`}
          </span>
        </div>
      ) : (
        <div className="cost-metric">
          <span className="t-label">Bütçe</span>
          <span className="cost-metric__value" style={{ color: 'var(--fg-muted)' }}>
            sınırsız
          </span>
        </div>
      )}

      <div className="cost-bar__spacer" />

      {showStream && <StreamChip status={streamStatus} />}
    </div>
  );
}

function StreamChip({ status }: { status: StreamStatus | 'idle' }) {
  if (status === 'live') {
    return (
      <span className="stream-chip stream-chip--live">
        <span className="stream-dot" aria-hidden />
        <Radio size={12} aria-hidden />
        Canlı
      </span>
    );
  }
  if (status === 'reconnecting') {
    return (
      <span className="stream-chip stream-chip--reconnecting" role="status">
        <span className="stream-dot" aria-hidden />
        <RefreshCw size={12} aria-hidden className="spin" />
        Yeniden bağlanıyor…
      </span>
    );
  }
  if (status === 'connecting') {
    return (
      <span className="stream-chip" role="status">
        <span className="stream-dot" aria-hidden />
        Bağlanıyor…
      </span>
    );
  }
  return (
    <span className="stream-chip">
      <WifiOff size={12} aria-hidden />
      Canlı akış kapalı
    </span>
  );
}
