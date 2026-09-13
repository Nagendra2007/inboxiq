import type { ReactNode } from 'react';
import { cn } from '../../lib/cn';
import { AlertTriangleIcon, InfoIcon, CheckCircleIcon } from './Icons';

export function Spinner({ className = 'h-5 w-5' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={cn('animate-spin', className)} aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.2" strokeWidth="2.5" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
}: {
  icon?: ReactNode;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-16 text-center', className)}>
      {icon && (
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl border border-white/[0.06] bg-white/[0.03] text-white/40">
          {icon}
        </div>
      )}
      <p className="text-sm font-semibold text-white/80">{title}</p>
      {description && <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-white/45">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

type AlertTone = 'info' | 'warning' | 'danger' | 'success';

const ALERT_STYLES: Record<AlertTone, { box: string; icon: string }> = {
  info: { box: 'border-white/[0.08] bg-white/[0.03] text-white/70', icon: 'text-white/50' },
  warning: { box: 'border-amber-400/20 bg-amber-400/[0.06] text-amber-100/90', icon: 'text-amber-300' },
  danger: { box: 'border-rose-500/25 bg-rose-500/[0.07] text-rose-100/90', icon: 'text-rose-300' },
  success: { box: 'border-emerald-400/20 bg-emerald-400/[0.06] text-emerald-100/90', icon: 'text-emerald-300' },
};

export function Alert({
  tone = 'info',
  title,
  children,
  action,
  className,
}: {
  tone?: AlertTone;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  const Icon = tone === 'success' ? CheckCircleIcon : tone === 'info' ? InfoIcon : AlertTriangleIcon;
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn('flex gap-3 rounded-xl border px-4 py-3 text-sm', ALERT_STYLES[tone].box, className)}
    >
      <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', ALERT_STYLES[tone].icon)} />
      <div className="min-w-0 flex-1">
        {title && <p className="font-semibold">{title}</p>}
        {children && <div className={cn(title ? 'mt-1 opacity-80' : '', 'leading-relaxed')}>{children}</div>}
      </div>
      {action && <div className="shrink-0 self-center">{action}</div>}
    </div>
  );
}
