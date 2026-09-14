import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { DashboardApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { DashboardDto } from '../types';
import { useAuth } from '../context/AuthContext';
import { cn } from '../lib/cn';
import { firstName, formatCount, formatTimeAgo, greeting } from '../lib/format';
import { Button } from '../components/ui/Button';
import { Alert, EmptyState } from '../components/ui/Feedback';
import {
  AlertTriangleIcon,
  ArrowRightIcon,
  ChevronRightIcon,
  DashboardIcon,
  InboxIcon,
  MailIcon,
  RefreshIcon,
  ReplyIcon,
  ShieldCheckIcon,
  TodoIcon,
} from '../components/ui/Icons';

// Status steps from the validated palette (tailwind `status.*`). Priority is
// an urgency level, so it uses status semantics — always paired with a text
// label, never color alone.
const PRIORITY_SEGMENTS = [
  { key: 'HIGH', label: 'High priority', swatch: 'bg-status-critical', field: 'highPriority' },
  { key: 'MEDIUM', label: 'Medium priority', swatch: 'bg-status-warning', field: 'mediumPriority' },
  { key: 'LOW', label: 'Low priority', swatch: 'bg-status-neutral', field: 'lowPriority' },
] as const;

function StatTile({
  label,
  value,
  context,
  icon,
  to,
}: {
  label: string;
  value: number;
  context?: string;
  icon: ReactNode;
  to?: string;
}) {
  const body = (
    <>
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-white/50">{label}</p>
        <span className="text-white/25 transition group-hover:text-white/50">{icon}</span>
      </div>
      <p className="mt-3 text-[28px] font-semibold leading-none tracking-tight text-white">{formatCount(value)}</p>
      {context && <p className="mt-2 truncate text-xs text-white/35">{context}</p>}
    </>
  );
  const className = 'card group block p-5 transition';
  return to ? (
    <Link to={to} className={cn(className, 'hover:border-white/[0.12] hover:bg-ink-750')}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
  );
}

function PriorityMix({ stats }: { stats: DashboardDto }) {
  const rows = PRIORITY_SEGMENTS.map((segment) => ({ ...segment, value: stats[segment.field] }));
  const analyzed = rows.reduce((sum, row) => sum + row.value, 0);
  const pct = (value: number) => (analyzed === 0 ? 0 : Math.round((value / analyzed) * 100));
  const visible = rows.filter((row) => row.value > 0);

  return (
    <section className="card p-5 lg:col-span-2" aria-labelledby="priority-mix-title">
      <header className="flex items-baseline justify-between gap-4">
        <div>
          <h2 id="priority-mix-title" className="text-sm font-semibold text-white/90">
            Priority mix
          </h2>
          <p className="mt-0.5 text-xs text-white/40">
            Across {analyzed.toLocaleString()} analyzed {analyzed === 1 ? 'email' : 'emails'}
          </p>
        </div>
      </header>

      {analyzed === 0 ? (
        <p className="mt-6 text-sm text-white/40">No analyzed emails yet — sync your inbox to see how it breaks down.</p>
      ) : (
        <>
          {/* Stacked bar: 2px surface gaps between segments, square at the
              baseline, 4px rounded at the data end. Each segment is a link
              with a hover/focus tooltip; the legend below carries every value. */}
          <div className="mt-10 flex gap-[2px]" role="img" aria-label={rows.map((r) => `${r.label}: ${r.value}`).join(', ')}>
            {visible.map((row, i) => (
              <Link
                key={row.key}
                to={`/inbox?priority=${row.key}`}
                style={{ flexGrow: row.value, flexBasis: 0 }}
                className="group relative min-w-[6px] py-2 focus-visible:!ring-offset-ink-800"
                aria-label={`${row.label}: ${row.value} emails (${pct(row.value)}%)`}
              >
                <span
                  className={cn(
                    'block h-3.5 transition-opacity group-hover:opacity-90',
                    row.swatch,
                    i === visible.length - 1 && 'rounded-r-[4px]'
                  )}
                />
                <span
                  role="tooltip"
                  className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 -translate-x-1/2 whitespace-nowrap rounded-lg border border-white/10 bg-ink-600 px-2.5 py-1.5 text-xs text-white/85 opacity-0 shadow-elevated transition group-hover:opacity-100 group-focus-visible:opacity-100"
                >
                  <span className="font-semibold">{row.label}</span>
                  <span className="text-white/50"> · {row.value.toLocaleString()} · {pct(row.value)}%</span>
                </span>
              </Link>
            ))}
          </div>

          <table className="mt-4 w-full text-sm">
            <caption className="sr-only">Emails by priority</caption>
            <thead className="sr-only">
              <tr>
                <th scope="col">Priority</th>
                <th scope="col">Emails</th>
                <th scope="col">Share</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/[0.05]">
              {rows.map((row) => (
                <tr key={row.key} className="group">
                  <th scope="row" className="py-2.5 pr-3 text-left font-normal">
                    <Link to={`/inbox?priority=${row.key}`} className="flex items-center gap-2.5 text-white/70 transition group-hover:text-white">
                      <span className={cn('h-2.5 w-2.5 rounded-[3px]', row.swatch)} aria-hidden="true" />
                      {row.label}
                    </Link>
                  </th>
                  <td className="py-2.5 text-right font-medium tabular-nums text-white/85">{row.value.toLocaleString()}</td>
                  <td className="w-16 py-2.5 text-right tabular-nums text-white/40">{pct(row.value)}%</td>
                  <td className="w-8 py-2.5 text-right">
                    <ChevronRightIcon className="ml-auto h-4 w-4 text-white/20 transition group-hover:text-white/50" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}

function SafetyCard({ stats }: { stats: DashboardDto }) {
  const flagged = stats.highRisk + stats.mediumRisk;

  if (flagged === 0) {
    return (
      <section className="card flex flex-col p-5" aria-labelledby="safety-title">
        <h2 id="safety-title" className="text-sm font-semibold text-white/90">
          Safety signals
        </h2>
        <div className="flex flex-1 flex-col items-start justify-center py-6">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-400/10 text-emerald-300">
            <ShieldCheckIcon className="h-5 w-5" />
          </span>
          <p className="mt-3 text-sm font-medium text-white/85">No suspicious emails detected</p>
          <p className="mt-1 text-xs leading-relaxed text-white/40">
            Every email is checked for phishing and scam patterns as it’s analyzed.
          </p>
        </div>
      </section>
    );
  }

  const rows = [
    { level: 'HIGH', label: 'Possible high risk', value: stats.highRisk, iconClass: 'text-rose-300 bg-rose-500/10' },
    { level: 'MEDIUM', label: 'Possible risk', value: stats.mediumRisk, iconClass: 'text-amber-200 bg-amber-400/10' },
  ];

  return (
    <section className="card flex flex-col p-5" aria-labelledby="safety-title">
      <h2 id="safety-title" className="text-sm font-semibold text-white/90">
        Safety signals
      </h2>
      <p className="mt-0.5 text-xs text-white/40">Signals, not verdicts — review before you act.</p>
      <ul className="mt-4 flex-1 space-y-2">
        {rows.map((row) => (
          <li key={row.level}>
            <Link
              to={`/inbox?risk=${row.level}`}
              className="group flex items-center gap-3 rounded-xl border border-white/[0.06] px-3 py-2.5 transition hover:border-white/[0.12] hover:bg-white/[0.02]"
            >
              <span className={cn('flex h-8 w-8 items-center justify-center rounded-lg', row.iconClass)}>
                <AlertTriangleIcon className="h-4 w-4" />
              </span>
              <span className="flex-1 text-sm text-white/70">{row.label}</span>
              <span className="text-lg font-semibold text-white">{row.value.toLocaleString()}</span>
              <ChevronRightIcon className="h-4 w-4 text-white/20 transition group-hover:text-white/50" />
            </Link>
          </li>
        ))}
      </ul>
      <Link
        to={`/inbox?risk=${stats.highRisk > 0 ? 'HIGH' : 'MEDIUM'}`}
        className="mt-4 inline-flex items-center gap-1.5 text-xs font-semibold text-accent-400 hover:text-accent-300"
      >
        Review flagged emails
        <ArrowRightIcon className="h-3.5 w-3.5" />
      </Link>
    </section>
  );
}

function DashboardSkeleton() {
  return (
    <div aria-hidden="true" className="space-y-4">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="card space-y-4 p-5">
            <span className="skeleton h-3 w-20" />
            <span className="skeleton h-7 w-16" />
            <span className="skeleton h-2.5 w-24" />
          </div>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <div className="card h-64 p-5 lg:col-span-2">
          <span className="skeleton h-3.5 w-28" />
          <span className="skeleton mt-8 h-3.5 w-full" />
        </div>
        <div className="card h-64 p-5">
          <span className="skeleton h-3.5 w-28" />
        </div>
      </div>
    </div>
  );
}

export function DashboardPage() {
  const { user } = useAuth();
  const [stats, setStats] = useState<DashboardDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const connected = !!user?.gmailConnected;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setStats(await DashboardApi.get());
      setUpdatedAt(new Date());
    } catch (err) {
      setError(errorMessage(err, 'Could not load your overview.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (connected) load();
    else setLoading(false);
  }, [connected, load]);

  const name = firstName(user?.name);

  return (
    <div className="flex-1 overflow-y-auto scrollbar-thin">
      <div className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8 md:py-8">
        <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="eyebrow">{new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-white">
              {greeting()}
              {name ? `, ${name}` : ''}
            </h1>
            <p className="mt-1 text-sm text-white/45">Here’s where your inbox stands.</p>
          </div>
          {connected && (
            <div className="flex items-center gap-3">
              {updatedAt && <span className="text-xs text-white/35">Updated {formatTimeAgo(updatedAt)}</span>}
              <Button variant="secondary" size="sm" onClick={load} loading={loading && !!stats} icon={<RefreshIcon className="h-3.5 w-3.5" />}>
                Refresh
              </Button>
            </div>
          )}
        </header>

        {!connected ? (
          <div className="card">
            <EmptyState
              icon={<DashboardIcon className="h-5 w-5" />}
              title="Connect Gmail to see your overview"
              description="Priorities, safety signals and to-dos show up here once your inbox is synced."
              action={
                <Link
                  to="/inbox"
                  className="inline-flex h-9 items-center gap-2 rounded-lg bg-accent-500 px-4 text-sm font-semibold text-on-accent transition hover:bg-accent-400"
                >
                  Go to Inbox
                  <ArrowRightIcon className="h-4 w-4" />
                </Link>
              }
            />
          </div>
        ) : error && !stats ? (
          <Alert
            tone="danger"
            title="Your overview couldn’t be loaded"
            action={
              <Button size="sm" variant="secondary" onClick={load}>
                Retry
              </Button>
            }
          >
            {error}
          </Alert>
        ) : !stats ? (
          <DashboardSkeleton />
        ) : (
          <div className={cn('space-y-4 transition-opacity', loading && 'opacity-60')}>
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <StatTile
                label="Unread"
                value={stats.unreadEmails}
                context={`of ${stats.totalEmails.toLocaleString()} synced`}
                icon={<MailIcon className="h-4 w-4" />}
                to="/inbox?unread=1"
              />
              <StatTile
                label="Awaiting your reply"
                value={stats.awaitingReply}
                context="AI-detected reply requests"
                icon={<ReplyIcon className="h-4 w-4" />}
              />
              <StatTile
                label="Open to-dos"
                value={stats.openActionItems}
                context="Extracted from your email"
                icon={<TodoIcon className="h-4 w-4" />}
                to="/action-items"
              />
              <StatTile
                label="Received this week"
                value={stats.receivedLast7Days}
                context="Last 7 days"
                icon={<InboxIcon className="h-4 w-4" />}
                to="/inbox"
              />
            </div>
            <div className="grid gap-4 lg:grid-cols-3">
              <PriorityMix stats={stats} />
              <SafetyCard stats={stats} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
