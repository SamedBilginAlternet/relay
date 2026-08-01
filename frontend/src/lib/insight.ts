import { AlertTriangle, CircleDot, GitPullRequest, Mail, SquareKanban } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { InsightSource, InsightUrgency } from '../types/brief';

/**
 * How a source and an urgency look, in one place.
 *
 * These used to live inside the big insight card, which meant every other
 * component that wanted the Jira icon had to import a card it did not render.
 * The feed made that untenable — a source is a *badge* now, worn by rows that
 * have nothing else in common with each other.
 */
export const SOURCE_META: Record<InsightSource, { Icon: LucideIcon; label: string }> = {
  gmail: { Icon: Mail, label: 'E-posta' },
  github: { Icon: GitPullRequest, label: 'GitHub' },
  jira: { Icon: SquareKanban, label: 'Jira' },
};

/** Colour never carries the meaning alone — icon + word always ride along. */
export const URGENCY_META: Record<
  InsightUrgency,
  { label: string; className: string; Icon: LucideIcon }
> = {
  high: { label: 'Acil', className: 'urgency--high', Icon: AlertTriangle },
  normal: { label: 'Normal', className: 'urgency--normal', Icon: CircleDot },
  low: { label: 'Düşük', className: 'urgency--low', Icon: CircleDot },
};

const KIND_LABEL: Record<string, string> = {
  bug_report: 'hata bildirimi',
  request: 'istek',
  fyi: 'bilgilendirme',
  needs_reply: 'yanıt bekliyor',
  scheduling: 'takvim',
};

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind.replace(/_/g, ' ');
}
