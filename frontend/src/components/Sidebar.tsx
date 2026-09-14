import { NavLink } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { DashboardDto, UserDto } from '../types';
import { cn } from '../lib/cn';
import { formatCount } from '../lib/format';
import { Avatar } from './ui/Avatar';
import { Logo } from './ui/Logo';
import { DashboardIcon, InboxIcon, KeyboardIcon, PenIcon, SettingsIcon, TodoIcon } from './ui/Icons';

interface NavItem {
  to: string;
  label: string;
  shortLabel: string;
  icon: (props: { className?: string }) => ReactNode;
  count?: (stats: DashboardDto) => number;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/inbox', label: 'Inbox', shortLabel: 'Inbox', icon: InboxIcon, count: (s) => s.unreadEmails },
  { to: '/dashboard', label: 'Overview', shortLabel: 'Overview', icon: DashboardIcon },
  { to: '/action-items', label: 'To-dos', shortLabel: 'To-dos', icon: TodoIcon, count: (s) => s.openActionItems },
];

function CountBadge({ value, active }: { value: number; active: boolean }) {
  if (value <= 0) return null;
  return (
    <span
      className={cn(
        'ml-auto min-w-[1.5rem] rounded-full px-1.5 py-px text-center text-[11px] font-semibold tabular-nums',
        active ? 'bg-accent-500/15 text-accent-300' : 'bg-white/[0.06] text-white/50'
      )}
    >
      {formatCount(value)}
    </span>
  );
}

export function Sidebar({
  user,
  stats,
  onCompose,
  onShortcuts,
}: {
  user: UserDto;
  stats: DashboardDto | null;
  onCompose: () => void;
  onShortcuts: () => void;
}) {
  const displayName = user.name || user.email.split('@')[0];

  return (
    <aside className="hidden w-[248px] shrink-0 flex-col border-r border-white/[0.06] bg-ink-850 md:flex">
      <div className="flex h-16 items-center px-5">
        <Logo />
      </div>

      <div className="px-3">
        <button
          type="button"
          onClick={onCompose}
          disabled={!user.gmailConnected}
          title={user.gmailConnected ? 'Compose (C)' : 'Connect Gmail to compose'}
          className="group flex h-10 w-full items-center gap-2 rounded-xl bg-accent-500 px-3.5 text-sm font-semibold text-ink-900 shadow-glow transition hover:bg-accent-400 disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none"
        >
          <PenIcon className="h-4 w-4" />
          Compose
          <span className="ml-auto rounded-md bg-ink-900/15 px-1.5 text-[10px] font-bold text-ink-900/70">C</span>
        </button>
      </div>

      <nav aria-label="Main" className="mt-5 flex flex-1 flex-col gap-0.5 px-3">
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'group flex h-9 items-center gap-3 rounded-lg px-3 text-sm font-medium transition',
                isActive ? 'bg-white/[0.07] text-white' : 'text-white/55 hover:bg-white/[0.04] hover:text-white/85'
              )
            }
          >
            {({ isActive }) => (
              <>
                <item.icon className={cn('h-[18px] w-[18px]', isActive ? 'text-accent-400' : 'text-white/40 group-hover:text-white/60')} />
                {item.label}
                {stats && item.count && <CountBadge value={item.count(stats)} active={isActive} />}
              </>
            )}
          </NavLink>
        ))}
      </nav>

      <div className="space-y-0.5 px-3 pb-3">
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            cn(
              'group flex h-9 items-center gap-3 rounded-lg px-3 text-sm font-medium transition',
              isActive ? 'bg-white/[0.07] text-white' : 'text-white/55 hover:bg-white/[0.04] hover:text-white/85'
            )
          }
        >
          {({ isActive }) => (
            <>
              <SettingsIcon className={cn('h-[18px] w-[18px]', isActive ? 'text-accent-400' : 'text-white/40 group-hover:text-white/60')} />
              Settings
            </>
          )}
        </NavLink>
        <button
          type="button"
          onClick={onShortcuts}
          className="group flex h-9 w-full items-center gap-3 rounded-lg px-3 text-sm font-medium text-white/55 transition hover:bg-white/[0.04] hover:text-white/85"
        >
          <KeyboardIcon className="h-[18px] w-[18px] text-white/40 group-hover:text-white/60" />
          Shortcuts
          <span className="kbd ml-auto">?</span>
        </button>
      </div>

      <div className="border-t border-white/[0.06] p-3">
        <NavLink
          to="/settings"
          className="flex items-center gap-3 rounded-xl px-2 py-2 transition hover:bg-white/[0.04]"
        >
          <span className="relative">
            <Avatar name={displayName} seed={user.email} size="sm" />
            <span
              className={cn(
                'absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-ink-850',
                user.gmailConnected && !user.gmailReauthRequired ? 'bg-emerald-400' : 'bg-amber-400'
              )}
              aria-hidden="true"
            />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-white/85">{displayName}</span>
            <span className="block truncate text-xs text-white/40">
              {!user.gmailConnected ? 'Gmail not connected' : user.gmailReauthRequired ? 'Reconnect Gmail' : user.email}
            </span>
          </span>
        </NavLink>
      </div>
    </aside>
  );
}

export function MobileTabBar({ stats }: { stats: DashboardDto | null }) {
  const items = [...NAV_ITEMS, { to: '/settings', label: 'Settings', shortLabel: 'Settings', icon: SettingsIcon } as NavItem];
  return (
    <nav
      aria-label="Main"
      className="fixed inset-x-0 bottom-0 z-30 flex border-t border-white/[0.06] bg-ink-850/90 backdrop-blur-lg safe-bottom md:hidden"
    >
      {items.map((item) => {
        const count = stats && item.count ? item.count(stats) : 0;
        return (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'relative flex h-[3.75rem] flex-1 flex-col items-center justify-center gap-1 text-[11px] font-medium transition',
                isActive ? 'text-accent-400' : 'text-white/40'
              )
            }
          >
            <span className="relative">
              <item.icon className="h-5 w-5" />
              {count > 0 && (
                <span className="absolute -right-2.5 -top-1.5 min-w-[1.1rem] rounded-full bg-accent-500 px-1 text-center text-[10px] font-bold leading-4 text-ink-900">
                  {count > 99 ? '99+' : count}
                </span>
              )}
            </span>
            {item.shortLabel}
          </NavLink>
        );
      })}
    </nav>
  );
}

export function MobileComposeButton({ onCompose }: { onCompose: () => void }) {
  return (
    <button
      type="button"
      onClick={onCompose}
      aria-label="Compose new email"
      className="fixed bottom-[calc(4.75rem+env(safe-area-inset-bottom))] right-4 z-30 flex h-14 w-14 items-center justify-center rounded-2xl bg-accent-500 text-ink-900 shadow-glow transition active:scale-95 md:hidden"
    >
      <PenIcon className="h-5 w-5" strokeWidth={2} />
    </button>
  );
}
