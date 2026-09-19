import { memo } from 'react';
import type { EmailSummaryDto } from '../types';
import { cn } from '../lib/cn';
import { formatListTime, parseSender } from '../lib/format';
import { useSwipeToDelete } from '../hooks/useSwipeToDelete';
import { Avatar } from './ui/Avatar';
import { CheckIcon, SparklesIcon, TrashIcon } from './ui/Icons';

/**
 * Queued or running in the background. New emails arrive with a PENDING
 * analysis; an email with no analysis at all predates background analysis
 * and just shows its snippet (it's analyzed when opened).
 */
export function isAnalysisPending(email: Pick<EmailSummaryDto, 'analysis'>): boolean {
  return email.analysis?.analysisStatus === 'PENDING';
}

/**
 * Intentionally minimal, per the original "that's all" brief: sender,
 * subject, a multi-line AI summary, and the time — nothing else.
 * Priority/risk/category live in the detail pane, not cluttering the row.
 *
 * Two ways to remove one: the trash button on hover, or — on a phone —
 * swiping the row to the left. Either way the confirmation is the same.
 * The avatar doubles as the selection checkbox, so several can go at once.
 */
export const EmailListItem = memo(function EmailListItem({
  email,
  active,
  selected,
  selecting,
  onSelect,
  onToggleSelected,
  onDelete,
}: {
  email: EmailSummaryDto;
  active: boolean;
  selected: boolean;
  /** True while a selection is in progress, so every row shows its checkbox. */
  selecting: boolean;
  onSelect: (id: string) => void;
  onToggleSelected: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const sender = parseSender(email.sender);
  const pending = isAnalysisPending(email);
  const summary = email.analysis?.summary || email.snippet;
  const unread = !email.read;
  // Swiping is for reaching one email; during a selection the checkboxes are
  // the point, and a stray swipe would fight them.
  const swipe = useSwipeToDelete(() => onDelete(email.id), !selecting);

  return (
    <li className="group relative overflow-hidden">
      {/* Revealed as the row slides away. */}
      <span
        aria-hidden="true"
        className={cn(
          'absolute inset-y-0 right-0 flex w-32 items-center justify-end gap-2 pr-5 text-xs font-semibold transition-colors',
          swipe.armed ? 'bg-rose-500/25 text-rose-100' : 'bg-rose-500/10 text-rose-200/70'
        )}
        style={{ opacity: swipe.offset < 0 ? 1 : 0 }}
      >
        <TrashIcon className="h-4 w-4" />
        Delete
      </span>

      <div
        className={cn('relative bg-ink-850', !swipe.swiping && 'transition-transform duration-200')}
        style={{ transform: `translateX(${swipe.offset}px)`, touchAction: 'pan-y' }}
        {...swipe.handlers}
      >
        <button
          type="button"
          onClick={() => {
            // The tail of a swipe must not also open the email.
            if (swipe.wasSwipe()) return;
            onSelect(email.id);
          }}
          aria-current={active ? 'true' : undefined}
          data-email-id={email.id}
          className={cn(
            'flex w-full gap-3 border-b border-white/[0.04] py-3.5 pl-4 pr-4 text-left transition focus-visible:!ring-inset focus-visible:!ring-offset-0',
            selected ? 'bg-accent-500/[0.08]' : active ? 'bg-white/[0.06]' : 'hover:bg-white/[0.025]'
          )}
        >
          {active && <span className="absolute inset-y-0 left-0 w-0.5 bg-accent-500" aria-hidden="true" />}
          <span className="relative mt-0.5">
            <Avatar name={sender.name} seed={sender.email || sender.name} size="md" />
            {unread && !selected && (
              <span
                className="absolute -left-1 -top-0.5 h-2.5 w-2.5 rounded-full bg-accent-500 ring-2 ring-ink-850"
                aria-label="Unread"
              />
            )}
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-baseline justify-between gap-2">
              <span className={cn('truncate text-sm', unread ? 'font-semibold text-white' : 'font-medium text-white/65')}>
                {sender.name}
              </span>
              <span className={cn('shrink-0 text-xs tabular-nums transition-opacity group-hover:opacity-0', unread ? 'text-accent-300' : 'text-white/35')}>
                {formatListTime(email.receivedAt)}
              </span>
            </span>
            <span className={cn('mt-0.5 block truncate text-[13px]', unread ? 'font-medium text-white/85' : 'text-white/50')}>
              {email.subject || '(no subject)'}
            </span>
            {pending ? (
              <span className="mt-1.5 flex items-center gap-1.5 text-xs text-white/35">
                <SparklesIcon className="h-3 w-3 animate-pulse text-accent-500/70" />
                Analyzing…
              </span>
            ) : (
              summary && <span className="mt-1 line-clamp-2 text-xs leading-relaxed text-white/40">{summary}</span>
            )}
          </span>
        </button>

        {/* Sits exactly over the avatar: the row's picture becomes its checkbox. */}
        <button
          type="button"
          role="checkbox"
          aria-checked={selected}
          onClick={() => onToggleSelected(email.id)}
          aria-label={`Select email from ${sender.name}`}
          className={cn(
            'absolute left-4 top-[18px] flex h-9 w-9 items-center justify-center rounded-full transition',
            selected
              ? 'bg-accent-500 text-on-accent'
              : cn(
                  'bg-ink-800 text-transparent ring-1 ring-inset ring-white/20 hover:text-white/50',
                  selecting ? 'opacity-100' : 'opacity-0 focus-visible:opacity-100 group-hover:opacity-100'
                )
          )}
        >
          <CheckIcon className="h-4 w-4" strokeWidth={3} />
        </button>

        <button
          type="button"
          onClick={() => onDelete(email.id)}
          aria-label={`Delete email from ${sender.name}`}
          title="Delete"
          className="absolute right-3 top-3 flex h-7 w-7 items-center justify-center rounded-md text-white/40 opacity-0 transition hover:bg-rose-500/10 hover:text-rose-300 focus-visible:opacity-100 group-hover:opacity-100"
        >
          <TrashIcon className="h-3.5 w-3.5" />
        </button>
      </div>
    </li>
  );
});

export function EmailListSkeleton({ rows = 8 }: { rows?: number }) {
  return (
    <ul aria-hidden="true">
      {Array.from({ length: rows }, (_, i) => (
        <li key={i} className="flex gap-3 border-b border-white/[0.04] px-4 py-3.5">
          <span className="skeleton h-9 w-9 shrink-0 rounded-full" />
          <span className="flex-1 space-y-2 pt-0.5">
            <span className="flex justify-between gap-6">
              <span className="skeleton h-3 w-2/5" />
              <span className="skeleton h-3 w-10" />
            </span>
            <span className="skeleton block h-3 w-4/5" />
            <span className="skeleton block h-2.5 w-full" />
          </span>
        </li>
      ))}
    </ul>
  );
}
