import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '../../lib/cn';
import { Button } from './Button';
import { XIcon } from './Icons';

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Open dialogs, innermost last — only the top one reacts to Escape/Tab, so a
// confirm dialog opened from inside another dialog closes on its own.
const stack: string[] = [];

const SIZES = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
};

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  size?: keyof typeof SIZES;
  role?: 'dialog' | 'alertdialog';
  /** When false, Escape and clicking the backdrop do nothing (e.g. while a request is in flight). */
  dismissible?: boolean;
  bodyClassName?: string;
}

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  role = 'dialog',
  dismissible = true,
  bodyClassName,
}: DialogProps) {
  const id = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const dismissibleRef = useRef(dismissible);
  dismissibleRef.current = dismissible;

  useEffect(() => {
    if (!open) return;
    stack.push(id);
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const panel = panelRef.current;
    const initial =
      panel?.querySelector<HTMLElement>('[data-autofocus]') ?? panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel;
    initial?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (stack[stack.length - 1] !== id || !panel) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        if (dismissibleRef.current) onCloseRef.current();
        return;
      }
      if (event.key === 'Tab') {
        const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
          (el) => el.offsetParent !== null
        );
        if (focusables.length === 0) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKeyDown);

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      const index = stack.lastIndexOf(id);
      if (index !== -1) stack.splice(index, 1);
      if (stack.length === 0) document.body.style.overflow = previousOverflow;
      previouslyFocused?.focus?.();
    };
  }, [open, id]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/70 p-0 [.theme-light_&]:bg-black/30 backdrop-blur-[2px] animate-fade-in sm:items-center sm:p-4"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && dismissibleRef.current) onCloseRef.current();
      }}
    >
      <div
        ref={panelRef}
        role={role}
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={description ? `${id}-desc` : undefined}
        tabIndex={-1}
        className={cn(
          'flex max-h-[92dvh] w-full flex-col overflow-hidden rounded-t-2xl border border-white/[0.08] bg-ink-800 shadow-elevated outline-none animate-scale-in sm:max-h-[85dvh] sm:rounded-2xl',
          SIZES[size]
        )}
      >
        <div className="flex items-start justify-between gap-4 border-b border-white/[0.06] px-5 py-4">
          <div className="min-w-0">
            <h2 id={`${id}-title`} className="text-[15px] font-semibold text-white">
              {title}
            </h2>
            {description && (
              <p id={`${id}-desc`} className="mt-1 text-sm leading-relaxed text-white/50">
                {description}
              </p>
            )}
          </div>
          {dismissible && (
            <button
              type="button"
              onClick={() => onCloseRef.current()}
              aria-label="Close"
              className="-mr-1.5 -mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-white/40 transition hover:bg-white/[0.07] hover:text-white"
            >
              <XIcon className="h-4 w-4" />
            </button>
          )}
        </div>
        {children && <div className={cn('flex-1 overflow-y-auto px-5 py-4 scrollbar-thin', bodyClassName)}>{children}</div>}
        {footer && (
          <div className="flex flex-col-reverse gap-2 border-t border-white/[0.06] bg-ink-850/60 px-5 py-3.5 safe-bottom sm:flex-row sm:items-center sm:justify-end">
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}

export interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'danger' | 'primary';
  loading?: boolean;
  /** Require the user to type this exact text before confirming (for irreversible actions). */
  confirmText?: string;
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'danger',
  loading = false,
  confirmText,
}: ConfirmDialogProps) {
  const [typed, setTyped] = useState('');
  const inputId = useId();

  useEffect(() => {
    if (!open) setTyped('');
  }, [open]);

  const blocked = confirmText !== undefined && typed.trim() !== confirmText;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      size="sm"
      role="alertdialog"
      dismissible={!loading}
      footer={
        <>
          {/* Destructive dialogs focus Cancel, so a stray Enter never deletes. */}
          <Button
            variant="ghost"
            onClick={onClose}
            disabled={loading}
            data-autofocus={tone === 'danger' && confirmText === undefined ? true : undefined}
          >
            {cancelLabel}
          </Button>
          <Button
            variant={tone === 'danger' ? 'danger' : 'primary'}
            onClick={onConfirm}
            loading={loading}
            disabled={blocked}
            data-autofocus={tone === 'primary' && confirmText === undefined ? true : undefined}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {(children || confirmText !== undefined) && (
        <div className="space-y-3">
          {children}
          {confirmText !== undefined && (
            <div>
              <label htmlFor={inputId} className="field-label">
                Type <span className="font-mono font-semibold text-white/80">{confirmText}</span> to confirm
              </label>
              <input
                id={inputId}
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                autoComplete="off"
                spellCheck={false}
                data-autofocus
                className="field font-mono"
              />
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
}
