import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '../../lib/cn';
import { AlertTriangleIcon, CheckCircleIcon, InfoIcon, XIcon } from './Icons';

type ToastTone = 'success' | 'error' | 'info';

interface ToastItem {
  id: number;
  tone: ToastTone;
  title: string;
  description?: string;
}

type PushToast = (toast: Omit<ToastItem, 'id'>) => void;

const ToastContext = createContext<PushToast | null>(null);

const TONE = {
  success: { icon: CheckCircleIcon, className: 'text-emerald-300' },
  error: { icon: AlertTriangleIcon, className: 'text-rose-300' },
  info: { icon: InfoIcon, className: 'text-accent-400' },
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const push = useCallback<PushToast>(
    (toast) => {
      const id = nextId.current++;
      setToasts((current) => [...current.slice(-3), { ...toast, id }]);
      window.setTimeout(() => dismiss(id), toast.tone === 'error' ? 7000 : 4500);
    },
    [dismiss]
  );

  return (
    <ToastContext.Provider value={push}>
      {children}
      {createPortal(
        <div
          aria-live="polite"
          aria-relevant="additions"
          className="pointer-events-none fixed inset-x-0 bottom-20 z-[60] flex flex-col items-center gap-2 px-4 md:inset-x-auto md:bottom-6 md:right-6 md:items-end"
        >
          {toasts.map((toast) => {
            const { icon: Icon, className } = TONE[toast.tone];
            return (
              <div
                key={toast.id}
                role={toast.tone === 'error' ? 'alert' : 'status'}
                className="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-xl border border-white/[0.08] bg-ink-700/95 px-4 py-3 shadow-elevated backdrop-blur animate-slide-up"
              >
                <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', className)} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-white/90">{toast.title}</p>
                  {toast.description && <p className="mt-0.5 text-xs leading-relaxed text-white/50">{toast.description}</p>}
                </div>
                <button
                  type="button"
                  onClick={() => dismiss(toast.id)}
                  aria-label="Dismiss notification"
                  className="-mr-1 rounded-md p-0.5 text-white/30 transition hover:bg-white/10 hover:text-white/70"
                >
                  <XIcon className="h-3.5 w-3.5" />
                </button>
              </div>
            );
          })}
        </div>,
        document.body
      )}
    </ToastContext.Provider>
  );
}

export function useToast() {
  const push = useContext(ToastContext);
  if (!push) throw new Error('useToast must be used within ToastProvider');
  return useMemo(
    () => ({
      success: (title: string, description?: string) => push({ tone: 'success', title, description }),
      error: (title: string, description?: string) => push({ tone: 'error', title, description }),
      info: (title: string, description?: string) => push({ tone: 'info', title, description }),
    }),
    [push]
  );
}
