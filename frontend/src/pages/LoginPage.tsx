import { useState } from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import { startGoogleLogin } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Alert, Spinner } from '../components/ui/Feedback';
import { Logo } from '../components/ui/Logo';
import { ThemeSwitch } from '../components/ThemeToggle';
import {
  AlertTriangleIcon,
  CheckIcon,
  GoogleIcon,
  LockIcon,
  SendIcon,
  ShieldCheckIcon,
  SparklesIcon,
  TodoIcon,
} from '../components/ui/Icons';

// Codes the backend's OAuth2 success/failure handlers redirect back with.
const ERROR_MESSAGES: Record<string, string> = {
  google_oauth_failed: 'Google sign-in didn’t complete. If you cancelled on the Google screen, just try again.',
  no_email: 'Your Google account didn’t share an email address, so InboxIQ can’t connect it.',
  no_authorized_client: 'Google didn’t return the permissions InboxIQ needs. Please try again and approve access.',
  unexpected_auth_type: 'Something went wrong finishing sign-in. Please try again.',
};

const FEATURES = [
  { icon: SparklesIcon, title: 'Summaries that respect your time', text: 'Every email distilled to what matters, with key points and dates.' },
  { icon: ShieldCheckIcon, title: 'Phishing signals, explained', text: 'Rules and AI flag suspicious messages — and tell you why.' },
  { icon: TodoIcon, title: 'To-dos, pulled out for you', text: 'Tasks and deadlines extracted and tracked automatically.' },
  { icon: SendIcon, title: 'Replies you approve', text: 'Describe a reply in a line. Nothing is sent until you confirm.' },
];

function PreviewCard() {
  return (
    <div className="card relative w-full max-w-md overflow-hidden p-5 shadow-elevated" aria-hidden="true">
      <div className="flex items-center gap-3">
        <span className="flex h-9 w-9 items-center justify-center rounded-full bg-sky-400/15 text-xs font-semibold text-sky-200">
          PS
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-white/90">Priya Shah</p>
          <p className="truncate text-xs text-white/40">Q3 vendor contract — signature needed</p>
        </div>
        <span className="inline-flex h-6 items-center gap-1.5 rounded-md bg-rose-500/10 px-2 text-[11px] font-semibold text-rose-200 ring-1 ring-inset ring-rose-500/25">
          <span className="h-1.5 w-1.5 rounded-full bg-status-critical" />
          High priority
        </span>
      </div>
      <div className="mt-4 rounded-xl border border-accent-500/10 bg-accent-500/[0.04] p-4">
        <p className="eyebrow flex items-center gap-1.5 !text-accent-300/80">
          <SparklesIcon className="h-3 w-3" />
          AI summary
        </p>
        <p className="mt-2 text-sm leading-relaxed text-white/80">
          Priya needs your signature on the renewed vendor contract before Friday so procurement can release payment.
        </p>
        <ul className="mt-3 space-y-1.5 text-xs text-white/55">
          <li className="flex items-center gap-2">
            <CheckIcon className="h-3.5 w-3.5 text-accent-400" />
            Sign the contract by Fri, Sep 19
          </li>
          <li className="flex items-center gap-2">
            <CheckIcon className="h-3.5 w-3.5 text-accent-400" />
            Confirm the updated payment terms
          </li>
        </ul>
      </div>
    </div>
  );
}

export function LoginPage() {
  const { status, sessionExpired } = useAuth();
  const [params] = useSearchParams();
  const [redirecting, setRedirecting] = useState(false);

  if (status === 'authenticated') return <Navigate to="/inbox" replace />;

  const errorCode = params.get('error');
  const error = errorCode ? ERROR_MESSAGES[errorCode] ?? ERROR_MESSAGES.unexpected_auth_type : null;

  const signIn = () => {
    setRedirecting(true);
    startGoogleLogin();
  };

  return (
    <div className="relative flex min-h-dvh overflow-hidden bg-ink-900">
      {/* Ambient backdrop */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 opacity-[0.35] [background-image:linear-gradient(rgb(var(--fg)/0.035)_1px,transparent_1px),linear-gradient(90deg,rgb(var(--fg)/0.035)_1px,transparent_1px)] [background-size:48px_48px] [mask-image:radial-gradient(ellipse_at_30%_20%,black,transparent_70%)]"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -left-40 -top-40 h-[520px] w-[520px] rounded-full bg-accent-500/[0.07] blur-3xl"
      />

      <div className="absolute right-5 top-5 z-10 flex items-center gap-2.5 text-xs font-medium text-white/45">
        <span aria-hidden="true">Light mode</span>
        <ThemeSwitch />
      </div>

      <div className="relative mx-auto grid w-full max-w-6xl items-center gap-12 px-6 py-10 lg:grid-cols-[1.1fr_1fr] lg:px-10">
        {/* Story */}
        <div className="order-2 lg:order-1">
          <Logo className="hidden lg:inline-flex" />
          <h1 className="mt-0 max-w-lg text-3xl font-bold leading-[1.15] tracking-tight text-white lg:mt-10 lg:text-[44px]">
            Your inbox, <span className="text-accent-400">triaged</span> before you open it.
          </h1>
          <p className="mt-4 max-w-md text-[15px] leading-relaxed text-white/50">
            InboxIQ reads your Gmail so you don’t have to — summaries, priorities, safety signals and to-dos, with you in
            control of every send.
          </p>
          <ul className="mt-8 grid max-w-xl gap-5 sm:grid-cols-2">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-3">
                <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-white/[0.06] bg-white/[0.03] text-accent-400">
                  <Icon className="h-4 w-4" />
                </span>
                <div>
                  <p className="text-sm font-semibold text-white/85">{title}</p>
                  <p className="mt-0.5 text-sm leading-relaxed text-white/45">{text}</p>
                </div>
              </li>
            ))}
          </ul>
          <div className="mt-10 hidden lg:block">
            <PreviewCard />
          </div>
        </div>

        {/* Sign in */}
        <div className="order-1 flex flex-col items-center lg:order-2">
          <Logo className="mb-8 lg:hidden" />
          <div className="card w-full max-w-sm p-7 shadow-elevated animate-scale-in sm:p-8">
            <h2 className="text-xl font-semibold text-white">Sign in to InboxIQ</h2>
            <p className="mt-1.5 text-sm leading-relaxed text-white/50">
              Use the Google account whose Gmail you want to manage.
            </p>

            {error && (
              <Alert tone="danger" className="mt-5">
                {error}
              </Alert>
            )}
            {!error && sessionExpired && (
              <Alert tone="info" className="mt-5">
                Your session ended. Sign in again to pick up where you left off.
              </Alert>
            )}
            {status === 'unreachable' && (
              <p className="mt-5 flex items-start gap-2 text-xs text-amber-200/80">
                <AlertTriangleIcon className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                The server is waking up — sign-in may take a few extra seconds.
              </p>
            )}

            <button
              type="button"
              onClick={signIn}
              disabled={redirecting}
              className="mt-6 flex h-11 w-full items-center justify-center gap-3 rounded-xl bg-white text-sm font-semibold text-ink-900 shadow-sm transition hover:bg-white/90 active:scale-[0.99] disabled:cursor-wait disabled:opacity-80"
            >
              {redirecting ? <Spinner className="h-4 w-4" /> : <GoogleIcon />}
              {redirecting ? 'Redirecting to Google…' : 'Continue with Google'}
            </button>

            <div className="mt-6 space-y-2.5 border-t border-white/[0.06] pt-5">
              {[
                'Your Google password is never seen or stored',
                'Tokens are encrypted at rest (AES-256-GCM)',
                'Disconnect or delete your data any time',
              ].map((text) => (
                <p key={text} className="flex items-center gap-2 text-xs text-white/45">
                  <LockIcon className="h-3.5 w-3.5 shrink-0 text-white/30" />
                  {text}
                </p>
              ))}
            </div>
          </div>
          <p className="mt-6 max-w-sm text-center text-xs leading-relaxed text-white/30">
            InboxIQ requests Gmail read, send and trash access. AI flags possibilities, not certainties — always use your
            judgment.
          </p>
          <p className="mt-3 text-center text-xs text-white/30">Built by Team Syntrix</p>
        </div>
      </div>
    </div>
  );
}
