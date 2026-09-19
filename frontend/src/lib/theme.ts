/**
 * Light/dark theme. Purely visual: the palette lives in CSS variables
 * (src/index.css) and `html.theme-light` switches it. The choice is a
 * per-browser display preference kept in localStorage (nothing sensitive);
 * dark stays the default. public/assets/theme-init-v1.js applies a saved
 * light theme before the first paint using the same key and colors.
 */
export type Theme = 'dark' | 'light';

export const THEME_STORAGE_KEY = 'inboxiq-theme';
/** Fired on window whenever the theme changes in this tab. */
export const THEME_CHANGE_EVENT = 'inboxiq:theme';

// Browser UI (mobile address bar) colors: each theme's page background.
const THEME_COLOR: Record<Theme, string> = { dark: '#08090e', light: '#f4f5fa' };

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('theme-light') ? 'light' : 'dark';
}

export function storedTheme(): Theme {
  try {
    return window.localStorage.getItem(THEME_STORAGE_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

/** Switches the page's palette without saving the choice. */
export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('theme-light', theme === 'light');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme]);
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', theme);
}

/** Switches and remembers the theme for this browser. */
export function saveTheme(theme: Theme) {
  applyTheme(theme);
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage blocked: the theme still applies until the page reloads.
  }
  window.dispatchEvent(new CustomEvent<Theme>(THEME_CHANGE_EVENT, { detail: theme }));
}
