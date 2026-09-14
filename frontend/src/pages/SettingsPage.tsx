import { useState, type ReactNode } from 'react';
import { AuthApi } from '../api/endpoints';
import { errorMessage, startGmailConnect } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useAppShell } from '../context/AppShell';
import { cn } from '../lib/cn';
import { Avatar } from '../components/ui/Avatar';
import { Button } from '../components/ui/Button';
import { ConfirmDialog } from '../components/ui/Dialog';
import { useToast } from '../components/ui/Toast';
import { AiSettingsSection } from '../components/AiSettingsSection';
import { AdministratorsSection } from '../components/AdministratorsSection';
import { ThemeSwitch } from '../components/ThemeToggle';
import {
  DatabaseIcon,
  EyeIcon,
  GoogleIcon,
  KeyboardIcon,
  LockIcon,
  LogOutIcon,
  SendIcon,
  TrashIcon,
  UnlinkIcon,
} from '../components/ui/Icons';

function Section({
  title,
  description,
  children,
  tone = 'default',
}: {
  title: string;
  description?: string;
  children: ReactNode;
  tone?: 'default' | 'danger';
}) {
  return (
    <section
      className={cn(
        'rounded-2xl border p-5 md:p-6',
        tone === 'danger' ? 'border-rose-500/20 bg-rose-500/[0.03]' : 'border-white/[0.06] bg-ink-800'
      )}
    >
      <h2 className={cn('text-sm font-semibold', tone === 'danger' ? 'text-rose-200' : 'text-white/90')}>{title}</h2>
      {description && <p className="mt-1 text-sm leading-relaxed text-white/45">{description}</p>}
      <div className="mt-5">{children}</div>
    </section>
  );
}

const PERMISSIONS = [
  { icon: EyeIcon, title: 'Read your email', detail: 'To summarize, prioritize and search your inbox.' },
  { icon: SendIcon, title: 'Send email', detail: 'Only replies and messages you explicitly confirm.' },
  { icon: TrashIcon, title: 'Move email to Trash', detail: 'When you delete in InboxIQ. Restorable in Gmail for 30 days.' },
];

export function SettingsPage() {
  const { user, refresh } = useAuth();
  const { refreshStats, openShortcuts } = useAppShell();
  const toast = useToast();
  const [signingOut, setSigningOut] = useState(false);
  const [confirm, setConfirm] = useState<'disconnect' | 'delete' | null>(null);
  const [busy, setBusy] = useState(false);

  if (!user) return null;
  const displayName = user.name || user.email.split('@')[0];
  const gmailHealthy = user.gmailConnected && !user.gmailReauthRequired;

  const logout = async () => {
    setSigningOut(true);
    try {
      await AuthApi.logout();
    } catch {
      // The session may already be gone; signing out locally is still right.
    }
    window.location.assign('/login');
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await AuthApi.disconnect();
      await refresh();
      refreshStats();
      setConfirm(null);
      toast.success('Gmail disconnected', 'Your synced emails are kept. Reconnect any time.');
    } catch (err) {
      toast.error('Could not disconnect Gmail', errorMessage(err, 'Please try again.'));
    } finally {
      setBusy(false);
    }
  };

  const deleteAllData = async () => {
    setBusy(true);
    try {
      await AuthApi.deleteAllData();
      await refresh();
      refreshStats();
      setConfirm(null);
      toast.success('All InboxIQ data deleted', 'Your emails, analyses, to-dos and drafts are gone for good.');
    } catch (err) {
      toast.error('Could not delete your data', errorMessage(err, 'Please try again.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex-1 overflow-y-auto scrollbar-thin">
      <div className="mx-auto w-full max-w-3xl space-y-5 px-4 py-6 md:px-8 md:py-8">
        <header className="mb-1">
          <h1 className="text-2xl font-semibold tracking-tight text-white">Settings</h1>
          <p className="mt-1 text-sm text-white/45">Your account, Gmail connection and data.</p>
        </header>

        <Section title="Account" description="You stay signed in on this browser, even after closing it, until you sign out or go 30 days without opening InboxIQ.">
          <div className="flex flex-wrap items-center gap-4">
            <Avatar name={displayName} seed={user.email} size="lg" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-white/90">{displayName}</p>
              <p className="truncate text-sm text-white/45">{user.email}</p>
            </div>
            <Button variant="secondary" onClick={logout} loading={signingOut} icon={<LogOutIcon className="h-4 w-4" />}>
              Sign out
            </Button>
          </div>
        </Section>

        <Section
          title="Gmail connection"
          description="InboxIQ connects through Google OAuth. Your password is never seen or stored, and access tokens are encrypted at rest."
        >
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-white/[0.06] bg-ink-900/50 px-4 py-3">
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-white">
              <GoogleIcon className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-white/85">Google account</p>
              <p className="truncate text-xs text-white/40">{user.email}</p>
            </div>
            <span
              className={cn(
                'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold',
                gmailHealthy ? 'bg-emerald-400/10 text-emerald-200' : 'bg-amber-400/10 text-amber-200'
              )}
            >
              <span className={cn('h-1.5 w-1.5 rounded-full', gmailHealthy ? 'bg-emerald-400' : 'bg-amber-400')} />
              {gmailHealthy ? 'Connected' : user.gmailConnected ? 'Reconnect needed' : 'Not connected'}
            </span>
          </div>

          <ul className="mt-4 space-y-3">
            {PERMISSIONS.map(({ icon: Icon, title, detail }) => (
              <li key={title} className="flex gap-3">
                <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-white/[0.04] text-white/50">
                  <Icon className="h-3.5 w-3.5" />
                </span>
                <div>
                  <p className="text-sm text-white/80">{title}</p>
                  <p className="text-xs text-white/40">{detail}</p>
                </div>
              </li>
            ))}
          </ul>

          <div className="mt-5 flex flex-wrap gap-2">
            {user.gmailReauthRequired && (
              <Button variant="primary" onClick={startGmailConnect} icon={<GoogleIcon className="h-4 w-4" />}>
                Reconnect Gmail
              </Button>
            )}
            {user.gmailConnected ? (
              <Button variant="danger-ghost" onClick={() => setConfirm('disconnect')} icon={<UnlinkIcon className="h-4 w-4" />}>
                Disconnect Gmail
              </Button>
            ) : (
              <Button variant="primary" onClick={startGmailConnect} icon={<GoogleIcon className="h-4 w-4" />}>
                Connect Gmail
              </Button>
            )}
          </div>
        </Section>

        {user.admin && <AiSettingsSection />}
        {user.admin && <AdministratorsSection currentEmail={user.email} />}

        <Section title="Appearance">
          <div className="flex items-center gap-4">
            <div className="min-w-0 flex-1">
              <p className="text-sm text-white/85">Light mode</p>
              <p className="mt-0.5 text-xs text-white/40">Use the light theme on this browser. Your choice is remembered.</p>
            </div>
            <ThemeSwitch />
          </div>
        </Section>

        <Section title="Keyboard shortcuts" description="Move through your inbox without touching the mouse.">
          <Button variant="secondary" onClick={openShortcuts} icon={<KeyboardIcon className="h-4 w-4" />}>
            View shortcuts
          </Button>
        </Section>

        <Section
          tone="danger"
          title="Delete all data"
          description="Permanently deletes every email, AI analysis, to-do and reply draft InboxIQ has stored for you, and disconnects Gmail. Your actual Gmail mailbox is not touched. This can’t be undone."
        >
          <Button variant="danger-ghost" onClick={() => setConfirm('delete')} icon={<DatabaseIcon className="h-4 w-4" />}>
            Delete all my data
          </Button>
        </Section>

        <div className="space-y-1 pt-2 text-center text-xs text-white/30">
          <p className="flex items-center justify-center gap-1.5">
            <LockIcon className="h-3 w-3" />
            InboxIQ v{__APP_VERSION__}
          </p>
          <p>Built by Team Syntrix</p>
        </div>
      </div>

      <ConfirmDialog
        open={confirm === 'disconnect'}
        onClose={() => !busy && setConfirm(null)}
        onConfirm={disconnect}
        loading={busy}
        title="Disconnect Gmail?"
        description="InboxIQ will stop syncing and won’t be able to send or trash email. Emails already synced are kept, and you can reconnect any time."
        confirmLabel="Disconnect"
      />
      <ConfirmDialog
        open={confirm === 'delete'}
        onClose={() => !busy && setConfirm(null)}
        onConfirm={deleteAllData}
        loading={busy}
        title="Delete all InboxIQ data?"
        description="Every synced email, analysis, to-do and draft will be permanently erased. Your Gmail mailbox itself is not affected."
        confirmLabel="Delete everything"
        confirmText="DELETE"
      />
    </div>
  );
}
