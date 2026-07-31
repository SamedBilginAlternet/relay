import { runStatusMeta } from '../lib/status';

export function StatusPill({ status }: { status: string }) {
  const meta = runStatusMeta(status);
  const Icon = meta.Icon;
  return (
    <span className={`status-pill ${meta.className}`}>
      <Icon size={14} aria-hidden />
      {meta.label}
    </span>
  );
}
