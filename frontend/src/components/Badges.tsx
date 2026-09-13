import type { Category, Priority, RiskLevel } from '../types';
import { titleCase } from '../lib/format';
import { cn } from '../lib/cn';
import { AlertTriangleIcon } from './ui/Icons';

const BASE = 'inline-flex h-6 items-center gap-1.5 rounded-md px-2 text-[11px] font-semibold ring-1 ring-inset';

const PRIORITY_STYLES: Record<Priority, { className: string; dot: string; label: string }> = {
  HIGH: { className: 'bg-rose-500/10 text-rose-200 ring-rose-500/25', dot: 'bg-status-critical', label: 'High priority' },
  MEDIUM: { className: 'bg-amber-400/10 text-amber-100 ring-amber-400/25', dot: 'bg-status-warning', label: 'Medium priority' },
  LOW: { className: 'bg-white/[0.04] text-white/55 ring-white/10', dot: 'bg-status-neutral', label: 'Low priority' },
};

export function PriorityBadge({ priority, score }: { priority: Priority | null | undefined; score?: number | null }) {
  if (!priority) return null;
  const style = PRIORITY_STYLES[priority];
  return (
    <span className={cn(BASE, style.className)} title={score != null ? `Priority score ${score}/100` : undefined}>
      <span className={cn('h-1.5 w-1.5 rounded-full', style.dot)} aria-hidden="true" />
      {style.label}
    </span>
  );
}

const RISK_STYLES: Record<Exclude<RiskLevel, 'LOW'>, string> = {
  HIGH: 'bg-rose-500/10 text-rose-200 ring-rose-500/30',
  MEDIUM: 'bg-orange-400/10 text-orange-100 ring-orange-400/25',
};

/** Risk is always phrased as a possibility, never a verdict. LOW renders nothing. */
export function RiskBadge({ level }: { level: RiskLevel | null | undefined }) {
  if (!level || level === 'LOW') return null;
  return (
    <span className={cn(BASE, RISK_STYLES[level])}>
      <AlertTriangleIcon className="h-3 w-3" />
      {level === 'HIGH' ? 'Possible high risk' : 'Possible risk'}
    </span>
  );
}

// Deliberately one muted, neutral style for every category — categories are
// informational labels, not something that needs its own color language
// (that's what priority/risk are for).
export function CategoryPill({ category }: { category: Category | null | undefined }) {
  if (!category) return null;
  return <span className={cn(BASE, 'bg-white/[0.04] text-white/60 ring-white/10')}>{titleCase(category)}</span>;
}

export function SignalPill({ children }: { children: string }) {
  return <span className={cn(BASE, 'bg-accent-500/[0.08] text-accent-200 ring-accent-500/20')}>{children}</span>;
}
