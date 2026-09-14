import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useRealtimeEvent } from '../context/RealtimeContext';
import type { AppShellContext } from '../context/AppShell';
import { DashboardApi } from '../api/endpoints';
import { startGmailConnect } from '../api/client';
import type { DashboardDto } from '../types';
import { useHotkeys } from '../hooks/useHotkeys';
import { Sidebar, MobileTabBar, MobileComposeButton } from '../components/Sidebar';
import { ComposeModal } from '../components/ComposeModal';
import { ShortcutsDialog } from '../components/ShortcutsDialog';
import { LogoMark } from '../components/ui/Logo';
import { Button } from '../components/ui/Button';
import { Spinner } from '../components/ui/Feedback';
import { AlertTriangleIcon, GoogleIcon, RefreshIcon, ServerIcon } from '../components/ui/Icons';

function SplashScreen() {
  return (
    <div className="flex h-dvh flex-col items-center justify-center gap-5 bg-ink-900" role="status" aria-label="Loading InboxIQ">
      <LogoMark className="h-12 w-12 animate-pulse" />
      <Spinner className="h-4 w-4 text-white/30" />
    </div>
  );
}

/**
 * Shown when the very first /api/auth/me call gets no usable answer. On a
 * free hosting tier this is almost always the server waking from sleep, so
 * it quietly retries instead of dumping the user on the sign-in page.
 */
function ServerUnreachable({ onRetry }: { onRetry: () => Promise<void> }) {
  const [retrying, setRetrying] = useState(false);

  const retry = useCallback(async () => {
    setRetrying(true);
    await onRetry();
    setRetrying(false);
  }, [onRetry]);

  useEffect(() => {
    const id = window.setInterval(retry, 8000);
    return () => window.clearInterval(id);
  }, [retry]);

  return (
    <div className="flex h-dvh items-center justify-center bg-ink-900 px-6">
      <div className="card w-full max-w-md p-8 text-center animate-scale-in">
        <div className="mx-auto mb-5 flex h-12 w-12 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.03] text-white/50">
          <ServerIcon className="h-5 w-5" />
        </div>
        <h1 className="text-lg font-semibold text-white">Waking up InboxIQ…</h1>
        <p className="mt-2 text-sm leading-relaxed text-white/50">
          We can't reach the server yet. If it's been idle for a while it may take up to a minute to start — we'll keep
          trying automatically.
        </p>
        <Button
          variant="secondary"
          className="mt-6"
          onClick={retry}
          loading={retrying}
          icon={<RefreshIcon className="h-4 w-4" />}
        >
          Try again now
        </Button>
      </div>
    </div>
  );
}

/**
 * Google stopped accepting InboxIQ's Gmail access (revoked in the Google
 * account, or expired). The user is still signed in and their stored mail
 * is all here; new mail just can't be fetched until they reconnect.
 */
function ReconnectGmailBanner() {
  return (
    <div
      role="alert"
      className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-b border-amber-400/15 bg-amber-400/[0.06] px-4 py-2.5 md:px-6"
    >
      <AlertTriangleIcon className="h-4 w-4 shrink-0 text-amber-300" />
      <p className="min-w-0 flex-1 text-sm text-amber-100/85">
        Gmail access has expired or was revoked. Your emails are still here — reconnect to keep receiving new ones.
      </p>
      <Button size="sm" variant="secondary" onClick={startGmailConnect} icon={<GoogleIcon className="h-3.5 w-3.5" />}>
        Reconnect Gmail
      </Button>
    </div>
  );
}

export function AppLayout() {
  const { user, status, refresh } = useAuth();
  const [composeOpen, setComposeOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [stats, setStats] = useState<DashboardDto | null>(null);
  const connected = !!user?.gmailConnected;
  const location = useLocation();
  // On phones the floating compose button would sit on top of the reply
  // composer, so it's hidden while an email is open.
  const readingEmail = location.pathname === '/inbox' && new URLSearchParams(location.search).has('email');

  const refreshStats = useCallback(() => {
    if (!connected) {
      setStats(null);
      return;
    }
    DashboardApi.get()
      .then(setStats)
      .catch(() => {
        // Badges are a nicety; the pages surface their own errors.
      });
  }, [connected]);

  useEffect(() => {
    refreshStats();
  }, [refreshStats]);

  // Keep the sidebar counts current as mail and analyses arrive. Bursts (a
  // first sync saves 20 emails in a few seconds) collapse into one refresh.
  const statsTimer = useRef<number>();
  const refreshStatsSoon = useCallback(() => {
    window.clearTimeout(statsTimer.current);
    statsTimer.current = window.setTimeout(refreshStats, 1200);
  }, [refreshStats]);
  useEffect(() => () => window.clearTimeout(statsTimer.current), []);
  useRealtimeEvent('email.saved', refreshStatsSoon);
  useRealtimeEvent('email.updated', refreshStatsSoon);
  useRealtimeEvent('email.deleted', refreshStatsSoon);
  useRealtimeEvent('email.analysis.completed', refreshStatsSoon);
  useRealtimeEvent('email.analysis.failed', refreshStatsSoon);
  useRealtimeEvent('resync', refreshStatsSoon);
  // Google revoked Gmail access mid-session: re-read /me to show the banner.
  useRealtimeEvent('sync.error', (event) => {
    if (event.reauthRequired) refresh();
  });

  const openCompose = useCallback(() => {
    if (connected) setComposeOpen(true);
  }, [connected]);
  const openShortcuts = useCallback(() => setShortcutsOpen(true), []);

  useHotkeys({ c: openCompose, '?': openShortcuts }, status === 'authenticated');

  const shell = useMemo<AppShellContext>(
    () => ({ stats, refreshStats, openCompose, openShortcuts }),
    [stats, refreshStats, openCompose, openShortcuts]
  );

  if (status === 'loading') return <SplashScreen />;
  if (status === 'unreachable') return <ServerUnreachable onRetry={refresh} />;
  if (!user) return <Navigate to="/login" replace />;

  return (
    <div className="flex h-dvh overflow-hidden bg-ink-900">
      <Sidebar user={user} stats={stats} onCompose={openCompose} onShortcuts={openShortcuts} />
      <main className="flex min-w-0 flex-1 flex-col overflow-hidden pb-[calc(3.75rem+env(safe-area-inset-bottom))] md:pb-0">
        {user.gmailConnected && user.gmailReauthRequired && <ReconnectGmailBanner />}
        <Outlet context={shell} />
      </main>
      <MobileTabBar stats={stats} />
      {connected && !readingEmail && <MobileComposeButton onCompose={openCompose} />}
      {composeOpen && (
        <ComposeModal
          onClose={() => setComposeOpen(false)}
          onSent={refreshStats}
        />
      )}
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />
    </div>
  );
}
