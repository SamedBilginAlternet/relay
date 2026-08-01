import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ShieldQuestion } from 'lucide-react';
import { useAwaitingRuns } from '../lib/awaitingRuns';
import '../styles/header-badge.css';

type Props = {
  /** Changes on every navigation — the cheapest honest moment to re-count. */
  routeKey: string;
  onNavigate: (hash: string) => void;
};

/**
 * "N onay bekliyor", on every screen.
 *
 * <p>Issue #72: a run that stops for a decision is invisible from anywhere except the
 * screen it was started on, and the live box had 32 of them stacked up with nobody told.
 * The panel screen knew the number all along — but you have to already suspect the problem
 * to go and look at the panel.
 *
 * <p>It renders nothing at all when nothing is waiting. A badge that is always there stops
 * being read, and a badge showing a zero is worse than none: it is a claim the app is idle,
 * made by a component whose only job is to say when it is not.
 */
export function ApprovalBadge({ routeKey, onNavigate }: Props) {
  const count = useAwaitingRuns(routeKey);
  const reduce = useReducedMotion();

  return (
    <AnimatePresence initial={false}>
      {count != null && count > 0 && (
        <motion.button
          type="button"
          className="gate-badge"
          // The number is not the sentence. Screen readers get the whole claim and where
          // pressing it goes; the bar gets two digits, because it has no room for more.
          aria-label={`${count} akış onayını bekliyor. Geçmiş ekranını aç.`}
          title={`${count} akış onayını bekliyor`}
          onClick={() => onNavigate('#/history')}
          initial={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.9 }}
          transition={{ duration: reduce ? 0.15 : 0.25, ease: [0.16, 1, 0.3, 1] }}
        >
          <ShieldQuestion size={14} aria-hidden />
          <span className="gate-badge__count" aria-hidden>
            {count}
          </span>
        </motion.button>
      )}
    </AnimatePresence>
  );
}
