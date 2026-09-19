import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ToastProvider } from './components/ui/Toast';
import { applyTheme, storedTheme } from './lib/theme';
import { prefetchInbox } from './api/prefetch';
import { INBOX_PAGE_SIZE } from './lib/constants';
import './index.css';

// Normally already applied before first paint by /assets/theme-init-v1.js;
// this covers the case where that script didn't run.
applyTheme(storedTheme());

// Start fetching mail now, in parallel with the session check, rather than
// after it. See api/prefetch.ts.
prefetchInbox(INBOX_PAGE_SIZE);

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
