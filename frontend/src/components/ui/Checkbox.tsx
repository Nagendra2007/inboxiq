import { cn } from '../../lib/cn';
import { CheckIcon } from './Icons';

/** Native checkbox (keyboard + screen-reader behavior for free) with a custom look. */
export function Checkbox({
  checked,
  onChange,
  label,
  className,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  className?: string;
}) {
  return (
    <span className={cn('relative inline-flex h-[18px] w-[18px] shrink-0', className)}>
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        aria-label={label}
        className="peer h-full w-full cursor-pointer appearance-none rounded-[6px] border border-white/25 bg-white/[0.02] transition checked:border-accent-500 checked:bg-accent-500 hover:border-white/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-ink-900"
      />
      <CheckIcon
        className="pointer-events-none absolute inset-0 m-auto h-3 w-3 text-ink-900 opacity-0 transition peer-checked:opacity-100"
        strokeWidth={3.2}
      />
    </span>
  );
}
