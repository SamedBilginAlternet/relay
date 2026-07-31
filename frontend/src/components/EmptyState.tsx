import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

type Props = {
  Icon: LucideIcon;
  title: string;
  description: string;
  children?: ReactNode;
};

/** NEVER-BLANK rule: every state says what is going on, out loud. */
export function EmptyState({ Icon, title, description, children }: Props) {
  return (
    <div className="empty">
      <span className="empty__icon">
        <Icon size={20} aria-hidden />
      </span>
      <p className="empty__title">{title}</p>
      <p className="t-caption">{description}</p>
      {children}
    </div>
  );
}
