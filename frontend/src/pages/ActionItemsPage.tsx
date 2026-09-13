import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ActionItemApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { ActionItemDto } from '../types';
import { useAuth } from '../context/AuthContext';
import { useAppShell } from '../context/AppShell';
import { cn } from '../lib/cn';
import { deadlineInfo, type DeadlineGroup } from '../lib/format';
import { Button } from '../components/ui/Button';
import { Checkbox } from '../components/ui/Checkbox';
import { Alert, EmptyState } from '../components/ui/Feedback';
import { useToast } from '../components/ui/Toast';
import { ArrowRightIcon, CalendarIcon, CheckCircleIcon, MailIcon, TodoIcon } from '../components/ui/Icons';

type Tab = 'open' | 'done';

const GROUPS: { key: DeadlineGroup; label: string }[] = [
  { key: 'overdue', label: 'Overdue' },
  { key: 'today', label: 'Today' },
  { key: 'week', label: 'This week' },
  { key: 'later', label: 'Later' },
  { key: 'none', label: 'No deadline' },
];

const DEADLINE_TONE: Record<DeadlineGroup, string> = {
  overdue: 'text-rose-300',
  today: 'text-amber-200',
  week: 'text-white/55',
  later: 'text-white/40',
  none: 'text-white/40',
};

function ItemRow({ item, onToggle }: { item: ActionItemDto; onToggle: (item: ActionItemDto, completed: boolean) => void }) {
  const due = deadlineInfo(item.deadline);
  return (
    <li className="flex items-start gap-3 px-4 py-3.5 transition hover:bg-white/[0.015] md:px-5">
      <Checkbox
        checked={item.completed}
        onChange={(checked) => onToggle(item, checked)}
        label={`Mark "${item.description}" as ${item.completed ? 'not done' : 'done'}`}
        className="mt-0.5"
      />
      <div className="min-w-0 flex-1">
        <p className={cn('text-sm leading-relaxed transition', item.completed ? 'text-white/30 line-through' : 'text-white/85')}>
          {item.description}
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
          {item.deadline && (
            <span className={cn('inline-flex items-center gap-1.5', item.completed ? 'text-white/30' : DEADLINE_TONE[due.group])}>
              <CalendarIcon className="h-3 w-3" />
              {due.label}
            </span>
          )}
          <Link
            to={`/inbox?email=${item.emailId}`}
            className="inline-flex min-w-0 max-w-full items-center gap-1.5 text-white/35 transition hover:text-white/70"
          >
            <MailIcon className="h-3 w-3 shrink-0" />
            <span className="truncate">{item.emailSubject || '(no subject)'}</span>
          </Link>
        </div>
      </div>
    </li>
  );
}

export function ActionItemsPage() {
  const { user } = useAuth();
  const { refreshStats } = useAppShell();
  const toast = useToast();
  const connected = !!user?.gmailConnected;
  const [items, setItems] = useState<ActionItemDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('open');
  // Items checked off during this visit stay (struck through) in the Open
  // list, so a mis-click is easy to spot and undo.
  const [recentlyDone, setRecentlyDone] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await ActionItemApi.list(0, 100);
      setItems(page.content);
    } catch (err) {
      setError(errorMessage(err, 'Could not load your to-dos.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (connected) load();
    else setLoading(false);
  }, [connected, load]);

  const toggle = useCallback(
    async (item: ActionItemDto, completed: boolean) => {
      setItems((prev) => prev.map((it) => (it.id === item.id ? { ...it, completed } : it)));
      setRecentlyDone((prev) => {
        const next = new Set(prev);
        if (completed) next.add(item.id);
        else next.delete(item.id);
        return next;
      });
      try {
        await ActionItemApi.setCompleted(item.id, completed);
        refreshStats();
      } catch (err) {
        setItems((prev) => prev.map((it) => (it.id === item.id ? { ...it, completed: !completed } : it)));
        toast.error('Could not update the to-do', errorMessage(err, 'Please try again.'));
      }
    },
    [refreshStats, toast]
  );

  const openItems = useMemo(() => items.filter((i) => !i.completed || recentlyDone.has(i.id)), [items, recentlyDone]);
  const doneItems = useMemo(() => items.filter((i) => i.completed), [items]);
  const openCount = items.filter((i) => !i.completed).length;
  const overdueCount = items.filter((i) => !i.completed && deadlineInfo(i.deadline).group === 'overdue').length;

  const grouped = useMemo(() => {
    const map = new Map<DeadlineGroup, ActionItemDto[]>();
    for (const item of openItems) {
      const group = deadlineInfo(item.deadline).group;
      map.set(group, [...(map.get(group) ?? []), item]);
    }
    return GROUPS.map((g) => ({ ...g, items: map.get(g.key) ?? [] })).filter((g) => g.items.length > 0);
  }, [openItems]);

  const tabs: { key: Tab; label: string; count: number }[] = [
    { key: 'open', label: 'Open', count: openCount },
    { key: 'done', label: 'Completed', count: doneItems.length },
  ];

  return (
    <div className="flex-1 overflow-y-auto scrollbar-thin">
      <div className="mx-auto w-full max-w-3xl px-4 py-6 md:px-8 md:py-8">
        <header className="mb-6">
          <h1 className="text-2xl font-semibold tracking-tight text-white">To-dos</h1>
          <p className="mt-1 text-sm text-white/45">
            {connected && !loading && !error
              ? `${openCount} open${overdueCount ? ` · ${overdueCount} overdue` : ''} — tasks and deadlines InboxIQ found in your email.`
              : 'Tasks and deadlines InboxIQ found in your email.'}
          </p>
        </header>

        {!connected ? (
          <div className="card">
            <EmptyState
              icon={<TodoIcon className="h-5 w-5" />}
              title="Connect Gmail to collect to-dos"
              description="InboxIQ pulls action items and deadlines out of your emails automatically."
              action={
                <Link
                  to="/inbox"
                  className="inline-flex h-9 items-center gap-2 rounded-lg bg-accent-500 px-4 text-sm font-semibold text-ink-900 transition hover:bg-accent-400"
                >
                  Go to Inbox
                  <ArrowRightIcon className="h-4 w-4" />
                </Link>
              }
            />
          </div>
        ) : error ? (
          <Alert
            tone="danger"
            title="Your to-dos couldn’t be loaded"
            action={
              <Button size="sm" variant="secondary" onClick={load}>
                Retry
              </Button>
            }
          >
            {error}
          </Alert>
        ) : (
          <>
            <div role="tablist" aria-label="To-do status" className="mb-4 inline-flex rounded-xl border border-white/[0.06] bg-ink-800 p-1">
              {tabs.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  role="tab"
                  aria-selected={tab === t.key}
                  onClick={() => setTab(t.key)}
                  className={cn(
                    'flex h-8 items-center gap-2 rounded-lg px-3.5 text-sm font-medium transition',
                    tab === t.key ? 'bg-white/[0.08] text-white' : 'text-white/45 hover:text-white/75'
                  )}
                >
                  {t.label}
                  {!loading && (
                    <span className={cn('text-xs tabular-nums', tab === t.key ? 'text-white/50' : 'text-white/30')}>{t.count}</span>
                  )}
                </button>
              ))}
            </div>

            {loading ? (
              <div className="card divide-y divide-white/[0.05]" aria-hidden="true">
                {Array.from({ length: 5 }, (_, i) => (
                  <div key={i} className="flex gap-3 px-5 py-4">
                    <span className="skeleton h-[18px] w-[18px] rounded-[6px]" />
                    <div className="flex-1 space-y-2">
                      <span className="skeleton h-3.5 w-3/4" />
                      <span className="skeleton h-3 w-1/3" />
                    </div>
                  </div>
                ))}
              </div>
            ) : tab === 'open' ? (
              grouped.length === 0 ? (
                <div className="card">
                  <EmptyState
                    icon={<CheckCircleIcon className="h-5 w-5 text-emerald-300" />}
                    title="You’re all caught up"
                    description="New tasks and deadlines appear here as InboxIQ analyzes incoming email."
                  />
                </div>
              ) : (
                <div className="space-y-5">
                  {grouped.map((group) => (
                    <section key={group.key} aria-labelledby={`group-${group.key}`}>
                      <h2
                        id={`group-${group.key}`}
                        className={cn('eyebrow mb-2 flex items-center gap-2 px-1', group.key === 'overdue' && '!text-rose-300/80')}
                      >
                        {group.label}
                        <span className="font-medium normal-case tracking-normal text-white/25">{group.items.length}</span>
                      </h2>
                      <ul className="card divide-y divide-white/[0.05] overflow-hidden">
                        {group.items.map((item) => (
                          <ItemRow key={item.id} item={item} onToggle={toggle} />
                        ))}
                      </ul>
                    </section>
                  ))}
                </div>
              )
            ) : doneItems.length === 0 ? (
              <div className="card">
                <EmptyState icon={<TodoIcon className="h-5 w-5" />} title="Nothing completed yet" description="Checked-off to-dos are kept here." />
              </div>
            ) : (
              <ul className="card divide-y divide-white/[0.05] overflow-hidden">
                {doneItems.map((item) => (
                  <ItemRow key={item.id} item={item} onToggle={toggle} />
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}
