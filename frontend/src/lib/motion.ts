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

/**
 * A row in the action feed: the standard entrance, plus what it does when the
 * list around it changes.
 *
 * The layout half is `position` only, and 220ms: the feed reorders when a job
 * is dismissed or a refresh comes back in a different order, and rows sliding
 * to their new places is the difference between "the list changed" and "did I
 * misread it?". Full `layout` would animate size too, which on a row with
 * clipped text means the text reflows mid-flight — that is the jitter this
 * deliberately does not buy. It gets its OWN transition so the reorder never
 * inherits the entrance's stagger delay; a row that waits 160ms before moving
 * looks broken. Off entirely under `prefers-reduced-motion`: someone who asked
 * for no movement did not ask for slower movement.
 */
export function feedRowProps(index: number, reduce: boolean | null) {
  const base = enterProps(index, reduce);
  return {
    ...base,
    layout: reduce ? (false as const) : ('position' as const),
    transition: {
      ...base.transition,
      layout: {
        duration: reduce ? 0.001 : 0.22,
        ease: [0.16, 1, 0.3, 1] as [number, number, number, number],
      },
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
