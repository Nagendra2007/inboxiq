import type { ReactNode } from 'react';
import type { AdjustButton } from '../types';
import { cn } from '../lib/cn';
import { Spinner } from './ui/Feedback';
import { BriefcaseIcon, RotateIcon, ShortenIcon, SmileIcon, type IconProps } from './ui/Icons';

export const ADJUST_OPTIONS: { key: AdjustButton; label: string; icon: (p: IconProps) => ReactNode }[] = [
  { key: 'MAKE_SHORTER', label: 'Shorter', icon: ShortenIcon },
  { key: 'MAKE_FORMAL', label: 'More formal', icon: BriefcaseIcon },
  { key: 'MAKE_FRIENDLY', label: 'Friendlier', icon: SmileIcon },
  { key: 'REGENERATE', label: 'Regenerate', icon: RotateIcon },
];

function wordCount(text: string): number {
  return text.trim() ? text.trim().split(/\s+/).length : 0;
}

/** Editable AI draft with one-click tone adjustments. */
export function DraftEditor({
  value,
  onChange,
  onAdjust,
  adjusting,
  disabled,
}: {
  value: string;
  onChange: (value: string) => void;
  onAdjust: (button: AdjustButton) => void;
  /** The adjustment currently being applied, if any. */
  adjusting: AdjustButton | null;
  disabled?: boolean;
}) {
  return (
    <div>
      <div className="relative">
        <textarea
          value={value}
          onChange={(event) => onChange(event.target.value)}
          rows={9}
          aria-label="Draft text"
          disabled={disabled}
          className="field min-h-[180px] resize-y leading-relaxed"
        />
        {adjusting && (
          <div className="absolute inset-0 flex items-center justify-center gap-2 rounded-lg bg-ink-800/75 text-sm text-white/70 backdrop-blur-[1px] animate-fade-in">
            <Spinner className="h-4 w-4 text-accent-500" />
            Rewriting your draft…
          </div>
        )}
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        {ADJUST_OPTIONS.map((option) => (
          <button
            key={option.key}
            type="button"
            onClick={() => onAdjust(option.key)}
            disabled={disabled || adjusting !== null}
            className={cn(
              'inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition disabled:cursor-not-allowed',
              adjusting === option.key
                ? 'border-accent-500/40 bg-accent-500/10 text-accent-300'
                : 'border-white/10 text-white/60 hover:border-white/20 hover:bg-white/[0.05] hover:text-white/90 disabled:opacity-50'
            )}
          >
            {adjusting === option.key ? <Spinner className="h-3 w-3" /> : <option.icon className="h-3.5 w-3.5" />}
            {option.label}
          </button>
        ))}
        <span className="ml-auto text-2xs tabular-nums text-white/30">{wordCount(value)} words</span>
      </div>
    </div>
  );
}

/** Final read-only preview of exactly what will be sent. */
export function SendPreview({ body }: { body: string }) {
  return (
    <div className="max-h-72 overflow-y-auto whitespace-pre-wrap rounded-xl border border-white/[0.06] bg-ink-900/60 p-4 text-sm leading-relaxed text-white/80 scrollbar-thin">
      {body}
    </div>
  );
}
