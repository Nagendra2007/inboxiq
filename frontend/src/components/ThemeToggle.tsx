import { cn } from '../lib/cn';
import { useTheme } from '../hooks/useTheme';
import { MoonIcon, SunIcon } from './ui/Icons';

/** The switch graphic: on = light theme. */
function SwitchTrack({ on, className }: { on: boolean; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border transition-colors duration-200',
        on ? 'border-transparent bg-accent-500' : 'border-white/10 bg-white/[0.08]',
        className
      )}
    >
      <span
        className={cn(
          'flex h-4 w-4 items-center justify-center rounded-full bg-paper shadow-sm transition-transform duration-200',
          on ? 'translate-x-[17px] text-accent-800' : 'translate-x-px text-on-accent'
        )}
      >
        {on ? <SunIcon className="h-2.5 w-2.5" strokeWidth={2.5} /> : <MoonIcon className="h-2.5 w-2.5" strokeWidth={2.5} />}
      </span>
    </span>
  );
}

/** Sidebar row, styled like the navigation items around it. */
export function ThemeToggleRow() {
  const { theme, toggle } = useTheme();
  const light = theme === 'light';
  const Icon = light ? SunIcon : MoonIcon;
  return (
    <button
      type="button"
      role="switch"
      aria-checked={light}
      onClick={toggle}
      className="group flex h-9 w-full items-center gap-3 rounded-lg px-3 text-sm font-medium text-white/55 transition hover:bg-white/[0.04] hover:text-white/85"
    >
      <Icon className="h-[18px] w-[18px] text-white/40 group-hover:text-white/60" />
      Light mode
      <SwitchTrack on={light} className="ml-auto" />
    </button>
  );
}

/** Standalone switch, for a place that shows its own label (Settings, sign-in). */
export function ThemeSwitch({ className }: { className?: string }) {
  const { theme, toggle } = useTheme();
  const light = theme === 'light';
  return (
    <button
      type="button"
      role="switch"
      aria-checked={light}
      aria-label="Light mode"
      title={light ? 'Switch to dark mode' : 'Switch to light mode'}
      onClick={toggle}
      className={cn('inline-flex rounded-full', className)}
    >
      <SwitchTrack on={light} />
    </button>
  );
}
