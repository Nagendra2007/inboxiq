import { useEffect, useRef } from 'react';

type HotkeyMap = Record<string, (event: KeyboardEvent) => void>;

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || !!target.closest('input, textarea, select');
}

/**
 * Single-key shortcuts (Gmail-style: "c", "j", "/"). Ignored while typing
 * in a field, while a modifier is held, and while any dialog is open, so a
 * shortcut can never fire behind a modal.
 */
export function useHotkeys(map: HotkeyMap, enabled = true) {
  const mapRef = useRef(map);
  mapRef.current = map;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTypingTarget(event.target)) return;
      if (document.querySelector('[role="dialog"], [role="alertdialog"]')) return;
      const handler = mapRef.current[event.key];
      if (handler) {
        event.preventDefault();
        handler(event);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
