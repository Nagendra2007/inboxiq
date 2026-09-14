// Applies the saved light theme before the app's first paint, so it never
// flashes dark first. A separate same-origin file because the app's
// Content-Security-Policy blocks inline scripts. Served from /assets/ with a
// long-lived cache: if this file changes, rename it (v2, ...) in index.html.
// Keep in sync with src/lib/theme.ts.
(function () {
  try {
    if (window.localStorage.getItem('inboxiq-theme') !== 'light') return;
    document.documentElement.classList.add('theme-light');
    var themeColor = document.querySelector('meta[name="theme-color"]');
    if (themeColor) themeColor.setAttribute('content', '#f3f5f4');
    var colorScheme = document.querySelector('meta[name="color-scheme"]');
    if (colorScheme) colorScheme.setAttribute('content', 'light');
  } catch (e) {
    // Storage unavailable (private mode, blocked): stay on the default theme.
  }
})();
