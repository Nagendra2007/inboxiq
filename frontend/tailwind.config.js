/** @type {import('tailwindcss').Config} */

// Theme-aware colors: every value here is a CSS variable (defined in
// src/index.css for the dark default and for `html.theme-light`), so the
// light theme is one palette swap rather than per-component classes.
const v = (name) => `rgb(var(--${name}) / <alpha-value>)`;

// Tailwind's own tints that the UI uses as *text* on tinted backgrounds
// (status pills, avatars). On the light theme they flip to the dark end of
// the same hue, so they stay readable on white. Only text flips — the same
// shade as a fill, border or ring keeps its normal value.
const TEXT_TINTS = {
  emerald: [100, 200, 300, 400],
  amber: [100, 200, 300, 400],
  rose: [100, 200, 300, 400],
  orange: [100, 200, 400],
  sky: [200, 400],
  violet: [200, 400],
  cyan: [200, 400],
  fuchsia: [200, 400],
  indigo: [200, 400],
  teal: [200, 400],
  blue: [200, 400],
  pink: [200, 400],
};
const textTints = Object.fromEntries(
  Object.entries(TEXT_TINTS).map(([hue, steps]) => [
    hue,
    Object.fromEntries(steps.map((step) => [step, v(`text-${hue}-${step}`)])),
  ])
);

export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // "white" is the foreground ink: white on the dark theme, near-black
        // on the light one. The UI draws text, hairlines and hover tints as
        // translucent foreground (text-white/60, bg-white/[0.04]); the light
        // theme strengthens those alphas a little (--fg-alpha-boost) because
        // dark-on-light at the same alpha reads fainter than light-on-dark.
        white: 'rgb(var(--fg) / calc(<alpha-value> * var(--fg-alpha-boost)))',
        // Indigo accent. Fills keep the same indigo on both themes — it is
        // dark enough to carry white lettering either way, which lime never
        // was; text and focus/selection lines use theme-aware steps (below).
        // 400/500/600 are the button's hover/rest/active and every one of
        // them clears 4.5:1 against white.
        accent: {
          50: '#f3f2fe',
          100: '#e7e4fd',
          200: '#d1ccfb',
          300: '#a99cf7',
          400: '#6b5dec',
          500: '#5d4fe0',
          600: '#4d3fc4',
          700: '#4034a2',
          800: '#332a80',
          900: '#221c55',
        },
        // Surfaces. Dark: 900 is the page, 800 raised panels, 750/700 hovered
        // or floating, 600+ controls. Light: the same roles on a light page.
        ink: {
          950: v('ink-950'),
          900: v('ink-900'),
          850: v('ink-850'),
          800: v('ink-800'),
          750: v('ink-750'),
          700: v('ink-700'),
          600: v('ink-600'),
          500: v('ink-500'),
          400: v('ink-400'),
        },
        // Theme-independent lettering. 'on-accent' rides the indigo fills and
        // the red danger fill; 'on-bright' is for the few pale fills (the
        // success button, the theme switch knob) where white would vanish.
        // 'paper' is the white an email body renders on.
        'on-accent': '#ffffff',
        'on-bright': '#0b0c14',
        'on-danger': '#ffffff',
        paper: '#ffffff',
        // Status steps are fixed across themes (validated for both surfaces;
        // they always ship with a label). Neutral is the recessive "low" mark
        // and steps with the surface.
        status: {
          good: '#0ca30c',
          warning: '#fab219',
          serious: '#ec835a',
          critical: '#d03b3b',
          neutral: v('status-neutral'),
        },
      },
      textColor: {
        ...textTints,
        // Lime reads well on dark but not as text on white: text uses the
        // theme's accent-ink steps instead.
        accent: {
          200: v('text-accent-200'),
          300: v('text-accent-300'),
          400: v('text-accent-400'),
          500: v('text-accent-500'),
        },
      },
      // Focus rings and selected-state borders need to stand out from a
      // white surface too, so the lime line darkens on the light theme.
      ringColor: { accent: { 500: v('accent-line') } },
      borderColor: { accent: { 500: v('accent-line') } },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },
      boxShadow: {
        elevated: 'var(--shadow-elevated)',
        glow: '0 0 0 1px rgba(93,79,224,0.45), 0 10px 30px -10px rgba(93,79,224,0.65)',
        inset: 'var(--shadow-inset)',
      },
      keyframes: {
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'scale-in': {
          from: { opacity: '0', transform: 'translateY(6px) scale(0.98)' },
          to: { opacity: '1', transform: 'translateY(0) scale(1)' },
        },
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(10px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        shimmer: { '100%': { transform: 'translateX(100%)' } },
      },
      animation: {
        'fade-in': 'fade-in 160ms ease-out',
        'scale-in': 'scale-in 200ms cubic-bezier(0.16, 1, 0.3, 1)',
        'slide-up': 'slide-up 240ms cubic-bezier(0.16, 1, 0.3, 1)',
        shimmer: 'shimmer 1.6s infinite',
      },
    },
  },
  plugins: [],
};
