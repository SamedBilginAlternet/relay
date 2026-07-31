/**
 * DESIGN.md §4 — one enter animation for the whole app: 300ms, 40ms stagger,
 * transform/opacity only. `prefers-reduced-motion` collapses it to a short
 * fade with no stagger, so nothing ever *moves* for a user who asked it not to.
 *
 * Keeping it here (instead of retyping the literals per component) is what
 * makes the stagger read as one wave across the screen instead of four
 * unrelated ones.
 */
export function enterProps(index: number, reduce: boolean | null) {
  const stagger = reduce ? 0 : Math.min(index, 8) * 0.04;
  return {
    initial: reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(8px)' },
    animate: { opacity: 1, transform: 'translateY(0px)' },
    exit: reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(-6px)' },
    transition: {
      duration: reduce ? 0.12 : 0.3,
      delay: stagger,
      ease: [0.16, 1, 0.3, 1] as [number, number, number, number],
    },
  };
}

/** 200ms open/close for inline detail (DESIGN.md §4). */
export function expandProps(reduce: boolean | null) {
  return {
    initial: { height: 0, opacity: 0 },
    animate: { height: 'auto' as const, opacity: 1 },
    exit: { height: 0, opacity: 0 },
    transition: { duration: reduce ? 0.001 : 0.2, ease: [0.16, 1, 0.3, 1] as [number, number, number, number] },
    style: { overflow: 'hidden' as const },
  };
}
