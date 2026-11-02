import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ToastProvider } from './components/ui/Toast';
import { applyTheme, storedTheme } from './lib/theme';
import { prefetchInbox } from './api/prefetch';
import { INBOX_PAGE_SIZE } from './lib/constants';
import { registerServiceWorker } from './lib/push';
import './index.css';

// Normally already applied before first paint by /assets/theme-init-v2.js;
// this covers the case where that script didn't run.
applyTheme(storedTheme());

// Start fetching mail now, in parallel with the session check, rather than
// after it. See api/prefetch.ts.
prefetchInbox(INBOX_PAGE_SIZE);

// Registering the worker is not the same as asking for permission. A push
// cannot be delivered without a registration in place beforehand, so this
// runs on every load; the prompt only ever comes from Settings. The worker
// caches nothing, so the site behaves identically with or without it.
void registerServiceWorker();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <ToastProvider>
          <App />
        </ToastProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>
);
