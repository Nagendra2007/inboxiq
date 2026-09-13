/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Lime accent + near-black surfaces: InboxIQ's UI reads as one
        // deliberate dark, high-contrast system rather than a generic
        // Tailwind default.
        accent: {
          50: '#f8ffe0',
          100: '#eeffc2',
          200: '#e6ff99',
          300: '#ddff70',
          400: '#d1ff3d',
          500: '#c8ff00',
          600: '#a3d600',
          700: '#7ea300',
          800: '#5c7700',
          900: '#3d4f00',
        },
        // Surfaces, darkest to lightest. 900 is the page, 800 raised panels,
        // 700 hovered/inset rows, 600+ borders and controls.
        ink: {
          950: '#020505',
          900: '#05090b',
          850: '#080d0f',
          800: '#0b1113',
          750: '#0e1517',
          700: '#111a1c',
          600: '#1a2528',
          500: '#263437',
          400: '#3a4a4e',
        },
        // Fixed status steps (validated for separation on the dark surface).
        // Used for data marks only; text stays on text tokens.
        status: {
          good: '#0ca30c',
          warning: '#fab219',
          serious: '#ec835a',
          critical: '#d03b3b',
          neutral: '#4a5356',
        },
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'ui-sans-serif', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },
      boxShadow: {
        elevated:
          '0 0 0 1px rgba(255,255,255,0.06), 0 12px 32px -12px rgba(0,0,0,0.7), 0 32px 80px -24px rgba(0,0,0,0.8)',
        glow: '0 0 0 1px rgba(200,255,0,0.35), 0 10px 30px -10px rgba(200,255,0,0.45)',
        inset: 'inset 0 1px 0 0 rgba(255,255,255,0.04)',
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
