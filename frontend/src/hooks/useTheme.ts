import { useCallback, useEffect, useState } from 'react';
import { THEME_CHANGE_EVENT, THEME_STORAGE_KEY, applyTheme, currentTheme, saveTheme, type Theme } from '../lib/theme';

/** The active theme, kept in sync across toggles on this page and in other tabs. */
export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(currentTheme);

  useEffect(() => {
    const sync = () => setThemeState(currentTheme());
    const onStorage = (event: StorageEvent) => {
      if (event.key !== THEME_STORAGE_KEY) return;
      applyTheme(event.newValue === 'light' ? 'light' : 'dark');
      sync();
    };
    window.addEventListener(THEME_CHANGE_EVENT, sync);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener(THEME_CHANGE_EVENT, sync);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  const toggle = useCallback(() => saveTheme(currentTheme() === 'light' ? 'dark' : 'light'), []);
  return { theme, toggle };
}
