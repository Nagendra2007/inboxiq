import { Component, type ErrorInfo, type ReactNode } from 'react';
import { LogoMark } from './ui/Logo';

interface State {
  hasError: boolean;
}

/** Last line of defense: a render error shows a recovery screen instead of a blank page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('InboxIQ crashed while rendering', error, info.componentStack);
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-5 bg-ink-900 px-6 text-center">
        <LogoMark className="h-11 w-11" />
        <div>
          <h1 className="text-lg font-semibold text-white">Something went wrong</h1>
          <p className="mt-1.5 max-w-sm text-sm text-white/50">
            InboxIQ hit an unexpected error. Your data is safe — reloading the page usually fixes this.
          </p>
        </div>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="h-10 rounded-xl bg-accent-500 px-5 text-sm font-semibold text-on-accent transition hover:bg-accent-400"
        >
          Reload InboxIQ
        </button>
      </div>
    );
  }
}
