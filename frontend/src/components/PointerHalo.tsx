import { useEffect } from 'react';
import { motion, useMotionValue, useReducedMotion, useSpring } from 'motion/react';

/**
 * A ring that follows the pointer and opens up over anything pressable.
 *
 * <p>Three decisions worth writing down, because each one is the difference
 * between a product that feels made and a party trick:
 *
 * <p><b>The real cursor stays.</b> Hiding it and drawing our own is the version
 * that looks best in a screenshot and fails worst in practice: one thrown
 * exception, one slow frame during a run, and the user is moving an invisible
 * pointer over a screen that authorises writes. This draws *next to* the system
 * cursor, so the worst case is a missing decoration.
 *
 * <p><b>It never touches React state.</b> A `setState` per `mousemove` would
 * re-render the whole tree sixty times a second, and this screen has a live SSE
 * stream running through it. Motion values write straight to the transform.
 *
 * <p><b>It is off wherever it would be wrong</b> — coarse pointers (there is no
 * cursor to decorate on a phone) and `prefers-reduced-motion`.
 */
export function PointerHalo() {
  const reduce = useReducedMotion();
  const x = useMotionValue(-100);
  const y = useMotionValue(-100);
  const size = useMotionValue(18);
  const opacity = useMotionValue(0);

  // Slightly behind the pointer, and softer over a target: the lag is what makes
  // it read as a following object rather than a second cursor competing with the
  // first one.
  const sx = useSpring(x, { stiffness: 900, damping: 45, mass: 0.35 });
  const sy = useSpring(y, { stiffness: 900, damping: 45, mass: 0.35 });
  const ssize = useSpring(size, { stiffness: 380, damping: 30 });
  const sopacity = useSpring(opacity, { stiffness: 220, damping: 30 });

  useEffect(() => {
    if (reduce) return;
    if (!window.matchMedia('(pointer: fine)').matches) return;

    const PRESSABLE = 'button, a, [role="button"], summary, input[type="checkbox"]';

    const move = (event: PointerEvent) => {
      x.set(event.clientX);
      y.set(event.clientY);
      opacity.set(1);
      const target = event.target as Element | null;
      size.set(target?.closest?.(PRESSABLE) ? 44 : 18);
    };
    const leave = () => opacity.set(0);

    window.addEventListener('pointermove', move, { passive: true });
    document.addEventListener('pointerleave', leave);
    window.addEventListener('blur', leave);
    return () => {
      window.removeEventListener('pointermove', move);
      document.removeEventListener('pointerleave', leave);
      window.removeEventListener('blur', leave);
    };
  }, [reduce, x, y, size, opacity]);

  if (reduce) return null;

  return (
    <motion.div
      className="halo"
      aria-hidden
      style={{
        translateX: sx,
        translateY: sy,
        width: ssize,
        height: ssize,
        opacity: sopacity,
      }}
    />
  );
}
