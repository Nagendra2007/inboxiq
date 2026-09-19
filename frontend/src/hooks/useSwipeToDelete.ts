import { useCallback, useRef, useState, type TouchEvent } from 'react';

/** How far left the row has to travel before letting go deletes it. */
const COMMIT_DISTANCE = 96;
/** Movement before we decide whether this is a swipe or a scroll. */
const DIRECTION_LOCK = 10;
/** Past this, the gesture counts as a swipe and shouldn't also open the email. */
const SWIPE_NOT_TAP = 6;

export interface SwipeToDelete {
  /** Current horizontal travel: 0 at rest, negative while swiping left. */
  offset: number;
  /** True while a left swipe is in progress, for suppressing transitions. */
  swiping: boolean;
  /** True when letting go now would delete — for showing the row as armed. */
  armed: boolean;
  handlers: {
    onTouchStart: (event: TouchEvent) => void;
    onTouchMove: (event: TouchEvent) => void;
    onTouchEnd: () => void;
    onTouchCancel: () => void;
  };
  /** Whether the gesture that just ended was a swipe rather than a tap. */
  wasSwipe: () => boolean;
}

/**
 * Swipe a list row left to delete it, the way a mail app on a phone does.
 *
 * Vertical scrolling stays the browser's: the element carries
 * `touch-action: pan-y`, so the browser owns up-and-down and hands us
 * left-and-right, which also means we never have to preventDefault on a
 * passive listener. Until the gesture has moved {@link DIRECTION_LOCK}
 * pixels we don't claim it at all, so a flick down the list never drags a
 * row sideways.
 *
 * Nothing is deleted here — it calls {@code onCommit}, and the caller asks
 * for confirmation exactly as the trash button does.
 */
export function useSwipeToDelete(onCommit: () => void, enabled = true): SwipeToDelete {
  const [offset, setOffset] = useState(0);
  const [swiping, setSwiping] = useState(false);
  const start = useRef<{ x: number; y: number } | null>(null);
  const axis = useRef<'undecided' | 'horizontal' | 'vertical'>('undecided');
  const travel = useRef(0);
  const swiped = useRef(false);

  const reset = useCallback(() => {
    start.current = null;
    axis.current = 'undecided';
    travel.current = 0;
    setOffset(0);
    setSwiping(false);
  }, []);

  const onTouchStart = useCallback(
    (event: TouchEvent) => {
      swiped.current = false;
      if (!enabled || event.touches.length !== 1) {
        start.current = null;
        return;
      }
      const touch = event.touches[0];
      start.current = { x: touch.clientX, y: touch.clientY };
      axis.current = 'undecided';
      travel.current = 0;
    },
    [enabled]
  );

  const onTouchMove = useCallback((event: TouchEvent) => {
    const origin = start.current;
    if (!origin) return;
    const touch = event.touches[0];
    const dx = touch.clientX - origin.x;
    const dy = touch.clientY - origin.y;

    if (axis.current === 'undecided') {
      if (Math.abs(dx) < DIRECTION_LOCK && Math.abs(dy) < DIRECTION_LOCK) return;
      axis.current = Math.abs(dx) > Math.abs(dy) ? 'horizontal' : 'vertical';
      if (axis.current === 'horizontal') setSwiping(true);
    }
    if (axis.current !== 'horizontal') return;

    // Left only: a rightward drag just returns the row to rest.
    const next = Math.min(0, dx);
    travel.current = next;
    if (next < -SWIPE_NOT_TAP) swiped.current = true;
    setOffset(next);
  }, []);

  const onTouchEnd = useCallback(() => {
    const commit = travel.current <= -COMMIT_DISTANCE;
    reset();
    if (commit) onCommit();
  }, [onCommit, reset]);

  const wasSwipe = useCallback(() => swiped.current, []);

  return {
    offset,
    swiping,
    armed: offset <= -COMMIT_DISTANCE,
    handlers: { onTouchStart, onTouchMove, onTouchEnd, onTouchCancel: reset },
    wasSwipe,
  };
}
