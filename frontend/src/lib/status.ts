import {
  Ban,
  Check,
  CircleDashed,
  CircleCheck,
  CircleX,
  Loader,
  Play,
  ShieldQuestion,
  SkipForward,
  Slash,
  Sparkles,
  X,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { StepStatus } from '../types/api';

export type StatusMeta = {
  label: string;
  /** colour class — colour is never the only signal, the icon carries it too */
  className: string;
  Icon: LucideIcon;
  spin?: boolean;
  pulse?: boolean;
};

/** DESIGN.md §1 — status = colour AND icon. */
export const STEP_STATUS: Record<StepStatus, StatusMeta> = {
  pending: { label: 'Bekliyor', className: 'st-pending', Icon: CircleDashed },
  awaiting_approval: { label: 'Onay bekliyor', className: 'st-awaiting', Icon: ShieldQuestion },
  running: { label: 'Çalışıyor', className: 'st-running', Icon: Loader, spin: true, pulse: true },
  done: { label: 'Tamamlandı', className: 'st-done', Icon: Check },
  failed: { label: 'Hata', className: 'st-failed', Icon: X },
  rejected: { label: 'Reddedildi', className: 'st-rejected', Icon: Slash },
  // Quiet on purpose — muted like rejected, never green: nothing ran. The glyph and the
  // word carry it (colour is never the only signal), and the row prints the reason.
  skipped: { label: 'Atlandı', className: 'st-skipped', Icon: SkipForward },
};

export function stepStatusMeta(status: StepStatus): StatusMeta {
  return STEP_STATUS[status] ?? STEP_STATUS.pending;
}

export const RUN_STATUS: Record<string, StatusMeta> = {
  planning: { label: 'Planlanıyor', className: 'st-running', Icon: Sparkles, pulse: true },
  running: { label: 'Çalışıyor', className: 'st-running', Icon: Play, pulse: true },
  awaiting_approval: { label: 'Onay bekliyor', className: 'st-awaiting', Icon: ShieldQuestion },
  done: { label: 'Tamamlandı', className: 'st-done', Icon: CircleCheck },
  failed: { label: 'Hata', className: 'st-failed', Icon: CircleX },
  cancelled: { label: 'İptal edildi', className: 'st-rejected', Icon: Ban },
};

export function runStatusMeta(status: string): StatusMeta {
  return (
    RUN_STATUS[status] ?? { label: status || 'Bilinmiyor', className: 'st-pending', Icon: CircleDashed }
  );
}

export const DECISION_LABEL: Record<string, string> = {
  auto: 'otomatik (politika: auto)',
  approved: 'kullanıcı onayladı',
  rejected: 'kullanıcı reddetti',
};
