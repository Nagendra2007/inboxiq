import { cn } from '../../lib/cn';
import { initials } from '../../lib/format';

// Muted tints only; rose/red and lime are left out so an avatar never
// reads as a risk signal or as the brand accent.
const TINTS = [
  'bg-sky-400/15 text-sky-200',
  'bg-violet-400/15 text-violet-200',
  'bg-emerald-400/15 text-emerald-200',
  'bg-cyan-400/15 text-cyan-200',
  'bg-fuchsia-400/15 text-fuchsia-200',
  'bg-indigo-400/15 text-indigo-200',
  'bg-teal-400/15 text-teal-200',
  'bg-orange-400/15 text-orange-200',
  'bg-blue-400/15 text-blue-200',
  'bg-pink-400/15 text-pink-200',
];

function hash(value: string): number {
  let h = 0;
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(h);
}

const SIZES = {
  sm: 'h-8 w-8 text-[11px]',
  md: 'h-9 w-9 text-xs',
  lg: 'h-11 w-11 text-sm',
};

/** Initials avatar with a stable per-person tint (same sender, same color). */
export function Avatar({
  name,
  seed,
  size = 'md',
  className,
}: {
  name: string;
  seed?: string;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const tint = TINTS[hash((seed || name).toLowerCase()) % TINTS.length];
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center rounded-full font-semibold ring-1 ring-inset ring-white/[0.06]',
        SIZES[size],
        tint,
        className
      )}
    >
      {initials(name)}
    </span>
  );
}
