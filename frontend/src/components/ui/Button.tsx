import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { cn } from '../../lib/cn';
import { Spinner } from './Feedback';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost' | 'success';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent-500 text-on-accent shadow-[0_1px_0_0_rgba(255,255,255,0.35)_inset] hover:bg-accent-400 active:bg-accent-600',
  secondary: 'border border-white/10 bg-white/[0.04] text-white/80 hover:border-white/15 hover:bg-white/[0.07] hover:text-white',
  ghost: 'text-white/60 hover:bg-white/[0.06] hover:text-white',
  danger: 'bg-rose-500 text-on-danger hover:bg-rose-400 active:bg-rose-600',
  'danger-ghost': 'border border-rose-500/25 text-rose-300 hover:border-rose-500/40 hover:bg-rose-500/10',
  success: 'bg-emerald-400 text-on-accent hover:bg-emerald-300 active:bg-emerald-500',
};

const SIZES: Record<ButtonSize, string> = {
  xs: 'h-7 gap-1.5 rounded-md px-2.5 text-xs',
  sm: 'h-8 gap-1.5 rounded-lg px-3 text-xs',
  md: 'h-9 gap-2 rounded-lg px-3.5 text-sm',
  lg: 'h-11 gap-2 rounded-xl px-5 text-sm',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  icon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon, className, children, disabled, type = 'button', ...rest },
  ref
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex select-none items-center justify-center whitespace-nowrap font-semibold transition duration-150 disabled:cursor-not-allowed disabled:opacity-50',
        VARIANTS[variant],
        SIZES[size],
        className
      )}
      {...rest}
    >
      {loading ? <Spinner className="h-3.5 w-3.5" /> : icon}
      {children}
    </button>
  );
});

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  size?: 'sm' | 'md';
  tone?: 'default' | 'danger';
}

/** Square, icon-only button. `label` is required: it becomes the accessible name and tooltip. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, size = 'md', tone = 'default', className, children, type = 'button', ...rest },
  ref
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={label}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-lg text-white/50 transition disabled:cursor-not-allowed disabled:opacity-40',
        size === 'sm' ? 'h-7 w-7' : 'h-9 w-9',
        tone === 'danger'
          ? 'hover:bg-rose-500/10 hover:text-rose-300'
          : 'hover:bg-white/[0.07] hover:text-white',
        className
      )}
      {...rest}
    >
      {children}
    </button>
  );
});
