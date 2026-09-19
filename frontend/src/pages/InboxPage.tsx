import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { EmailApi, GmailApi, type SearchFilters } from '../api/endpoints';
import { errorMessage, isAbortError, startGmailConnect } from '../api/client';
import { takePrefetchedInbox } from '../api/prefetch';
import { useAuth } from '../context/AuthContext';
import { useAppShell } from '../context/AppShell';
import { useRealtime, useRealtimeEvent, type RealtimeStatus } from '../context/RealtimeContext';
import type {
  BulkActionResultDto,
  Category,
  DashboardDto,
  EmailAnalysisDto,
  EmailDetailDto,
  EmailSummaryDto,
  Page,
  Priority,
  RiskLevel,
} from '../types';
import { cn } from '../lib/cn';
import { INBOX_PAGE_SIZE } from '../lib/constants';
import { formatCount, formatTimeAgo, listDayLabel, pluralize, titleCase } from '../lib/format';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useHotkeys } from '../hooks/useHotkeys';
import { EmailListItem, EmailListSkeleton, isAnalysisPending } from '../components/EmailListItem';
import { EmailDetailPane } from '../components/EmailDetailPane';
import { Button, IconButton } from '../components/ui/Button';
import { ConfirmDialog } from '../components/ui/Dialog';
import { Alert, EmptyState, Spinner } from '../components/ui/Feedback';
import { useToast } from '../components/ui/Toast';
import {
  ArchiveIcon,
  ChevronDownIcon,
  GoogleIcon,
  InboxIcon,
  LockIcon,
  MailIcon,
  RefreshIcon,
  SearchIcon,
  MailOpenIcon,
  SendIcon,
  ShieldCheckIcon,
  SparklesIcon,
  TrashIcon,
  XIcon,
} from '../components/ui/Icons';

const CATEGORIES: Category[] = ['PERSONAL', 'WORK', 'EDUCATION', 'FINANCE', 'SHOPPING', 'DELIVERY', 'SECURITY', 'SOCIAL', 'MARKETING', 'NEWSLETTER', 'SUSPICIOUS', 'OTHER'];
const PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];
const RISK_LEVELS: RiskLevel[] = ['HIGH', 'MEDIUM', 'LOW'];

const PAGE_SIZE = INBOX_PAGE_SIZE;
// Fallback only, while the realtime stream is down: watch for background analysis.
const LIST_POLL_MS = 5000;
const LIST_POLL_LIMIT = 24; // ~2 minutes
// Emails per bulk request; the backend refuses more than 100 at a time.
const BULK_ACTION_BATCH = 50;
const SYNC_FOLLOW_MS = 2000;
const SYNC_FOLLOW_LIMIT = 30;

/**
 * What actually happened in Gmail, rather than what usually happens. An
 * email Gmail no longer had (deleted there, or in another client, since the
 * last sync) still changes here — but claiming the user's mailbox changed
 * when it didn't would be a lie about their real mail.
 */
function gmailOutcome(applied: number, changedInGmail: number, didWhat: string): string {
  if (changedInGmail === applied) return `They were also ${didWhat}.`;
  if (changedInGmail === 0) return 'Gmail no longer had them, so only InboxIQ’s copies changed.';
  return `${changedInGmail} of them were ${didWhat}; Gmail no longer had the rest.`;
}

function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | '' {
  return value && (allowed as readonly string[]).includes(value) ? (value as T) : '';
}

const receivedTime = (email: EmailSummaryDto) => (email.receivedAt ? Date.parse(email.receivedAt) : 0) || 0;
const byNewest = (a: EmailSummaryDto, b: EmailSummaryDto) => receivedTime(b) - receivedTime(a);

/**
 * Merges a freshly fetched first page into the list: its rows win, rows it
 * doesn't cover (loaded further down) are kept, and rows inside its range
 * that it no longer contains were deleted and are dropped.
 */
function mergeFirstPage(prev: EmailSummaryDto[], fresh: EmailSummaryDto[], isWholeList: boolean): EmailSummaryDto[] {
  const freshIds = new Set(fresh.map((e) => e.id));
  const oldestFresh = fresh.length ? Math.min(...fresh.map(receivedTime)) : Infinity;
  const kept = prev.filter((e) => !freshIds.has(e.id) && !isWholeList && receivedTime(e) < oldestFresh);
  return [...fresh, ...kept].sort(byNewest);
}

function FilterSelect({
  value,
  onChange,
  label,
  children,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  children: ReactNode;
}) {
  return (
    <span className="relative shrink-0">
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-label={label}
        className={cn(
          'h-7 cursor-pointer appearance-none rounded-full border pl-3 pr-7 text-xs font-medium transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-500/50',
          value
            ? 'border-accent-500/40 bg-accent-500/10 text-accent-200'
            : 'border-white/10 bg-transparent text-white/55 hover:border-white/20 hover:text-white/80'
        )}
      >
        {children}
      </select>
      <ChevronDownIcon className="pointer-events-none absolute right-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-current opacity-60" />
    </span>
  );
}

function ConnectGmail() {
  const points = [
    { icon: SparklesIcon, text: 'AI summaries, priorities and to-dos for every email' },
    { icon: ShieldCheckIcon, text: 'Possible phishing and scam signals, explained' },
    { icon: SendIcon, text: 'Replies are only ever sent after you confirm them' },
  ];
  return (
    <div className="flex flex-1 items-center justify-center overflow-y-auto p-6">
      <div className="card w-full max-w-md p-8 animate-scale-in">
        <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-accent-500/10 text-accent-400">
          <MailIcon className="h-6 w-6" />
        </div>
        <h1 className="text-xl font-semibold text-white">Connect your Gmail</h1>
        <p className="mt-2 text-sm leading-relaxed text-white/50">
          InboxIQ needs permission to read, send and trash Gmail messages on your behalf. Your Google password is never
          seen or stored, and you can disconnect at any time.
        </p>
        <ul className="mt-5 space-y-2.5">
          {points.map(({ icon: Icon, text }) => (
            <li key={text} className="flex items-center gap-3 text-sm text-white/70">
              <Icon className="h-4 w-4 shrink-0 text-accent-400" />
              {text}
            </li>
          ))}
        </ul>
        <button
          type="button"
          onClick={startGmailConnect}
          className="mt-7 flex h-11 w-full items-center justify-center gap-3 rounded-xl bg-white text-sm font-semibold text-ink-900 transition hover:bg-white/90 active:scale-[0.99]"
        >
          <GoogleIcon />
          Connect with Google
        </button>
        <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-white/35">
          <LockIcon className="h-3.5 w-3.5" />
          OAuth tokens are encrypted at rest
        </p>
      </div>
    </div>
  );
}

/** Dot beside the title: green while new mail and summaries arrive by themselves. */
function LiveIndicator({ status }: { status: RealtimeStatus }) {
  if (status === 'off') return null;
  const live = status === 'open';
  const label = live
    ? 'Live — new mail appears automatically'
    : status === 'connecting'
      ? 'Connecting for live updates…'
      : 'Reconnecting — live updates are paused';
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={cn('h-1.5 w-1.5 rounded-full transition-colors', live ? 'bg-emerald-400' : 'bg-amber-400')}
    />
  );
}

/**
 * Half the window, whenever no email is open. Rather than an icon and an
 * apology, it's the three questions the analysis can already answer —
 * what's unread, what's urgent, what looks unsafe — each one a way into the
 * list it sits beside. The counts come from the sidebar stats that are
 * loaded anyway, so this costs no request of its own.
 */
function ReaderStartHere({ stats, onPick }: { stats: DashboardDto | null; onPick: (filter: string) => void }) {
  const routes = [
    { key: 'unread', label: 'Unread', value: stats?.unreadEmails, tone: 'text-accent-300', hint: 'Not opened yet' },
    { key: 'priority', label: 'High priority', value: stats?.highPriority, tone: 'text-status-warning', hint: 'Worth doing first' },
    { key: 'risk', label: 'Flagged', value: stats?.highRisk, tone: 'text-status-critical', hint: 'Check before acting' },
  ];

  return (
    <div className="flex h-full flex-col items-center justify-center px-8">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.06] bg-white/[0.02] text-accent-400">
            <SparklesIcon className="h-5 w-5" />
          </span>
          <div>
            <p className="text-sm font-semibold text-white/85">Nothing open</p>
            <p className="text-sm text-white/40">Pick an email, or start from here.</p>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-2.5">
          {routes.map(({ key, label, value, tone, hint }) => (
            <button
              key={key}
              type="button"
              onClick={() => onPick(key)}
              disabled={value === undefined}
              className="card group p-3.5 text-left transition hover:border-white/[0.12] hover:bg-ink-750 disabled:cursor-default disabled:opacity-60"
            >
              <p className={cn('text-[26px] font-semibold leading-none tracking-tight tabular-nums', tone)}>
                {value === undefined ? '—' : formatCount(value)}
              </p>
              <p className="mt-2 text-xs font-medium text-white/70">{label}</p>
              <p className="mt-0.5 text-[11px] leading-snug text-white/35">{hint}</p>
            </button>
          ))}
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-white/[0.06] pt-4 text-xs text-white/35">
          <span className="flex items-center gap-1.5">
            <kbd className="kbd">J</kbd>
            <kbd className="kbd">K</kbd> navigate
          </span>
          <span className="flex items-center gap-1.5">
            <kbd className="kbd">E</kbd> archive
          </span>
          <span className="flex items-center gap-1.5">
            <kbd className="kbd">/</kbd> search
          </span>
          <span className="flex items-center gap-1.5">
            <kbd className="kbd">C</kbd> compose
          </span>
        </div>
      </div>
    </div>
  );
}

export function InboxPage() {
  const { user } = useAuth();
  const { stats, refreshStats } = useAppShell();
  const toast = useToast();
  const connected = !!user?.gmailConnected;
  const [params, setParams] = useSearchParams();

  // --- URL-backed state (shareable, and deep-linkable from the dashboard) ---
  const q = params.get('q') ?? '';
  const category = oneOf(params.get('category'), CATEGORIES);
  const priority = oneOf(params.get('priority'), PRIORITIES);
  const riskLevel = oneOf(params.get('risk'), RISK_LEVELS);
  const unreadOnly = params.get('unread') === '1';
  /** The archived list rather than the inbox — a different list, not a filter within it. */
  const showingArchived = params.get('archived') === '1';
  const selectedId = params.get('email');

  const updateParams = useCallback(
    (changes: Record<string, string | null>, replace = true) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(changes)) {
            if (value === null || value === '') next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace }
      );
    },
    [setParams]
  );

  const [searchInput, setSearchInput] = useState(q);
  const debouncedSearch = useDebouncedValue(searchInput.trim(), 300);
  useEffect(() => {
    if (debouncedSearch !== q) updateParams({ q: debouncedSearch || null });
    // Only the debounced input drives the URL.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSearch]);

  const filters = useMemo<SearchFilters>(
    () => ({
      keyword: q || undefined,
      category: category || undefined,
      priority: priority || undefined,
      riskLevel: riskLevel || undefined,
      unreadOnly: unreadOnly || undefined,
      // Always sent: a search has to know which of the two lists it's in.
      archived: showingArchived,
    }),
    [q, category, priority, riskLevel, unreadOnly, showingArchived]
  );
  const hasFilters = !!(q || category || priority || riskLevel || unreadOnly);

  // --- List data ---
  const [emails, setEmails] = useState<EmailSummaryDto[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [refetching, setRefetching] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [pollExhausted, setPollExhausted] = useState(false);
  const requestId = useRef(0);
  const emailsRef = useRef(emails);
  emailsRef.current = emails;
  const selectedIdRef = useRef(selectedId);
  selectedIdRef.current = selectedId;
  // A resync that arrived while the list was still loading runs right after it.
  const reconcileAfterLoad = useRef(false);
  const reconcileRef = useRef<() => void>(() => {});

  /**
   * Rows on their way out. A confirmed delete takes them off screen at once
   * — the wait is Gmail's, and there is nothing to watch while it happens —
   * and they are held here so a list the server answers mid-delete doesn't
   * put them back for a moment. One that fails returns to its place, with
   * the message saying so.
   */
  const leaving = useRef<Set<string>>(new Set());
  const keepVisible = useCallback(
    (list: EmailSummaryDto[]) => (leaving.current.size === 0 ? list : list.filter((e) => !leaving.current.has(e.id))),
    []
  );

  const fetchPage = useCallback(
    (pageIndex: number, size: number, signal?: AbortSignal): Promise<Page<EmailSummaryDto>> =>
      hasFilters
        ? EmailApi.search(filters, pageIndex, size, signal)
        : EmailApi.list(pageIndex, size, showingArchived, signal),
    [filters, hasFilters, showingArchived]
  );

  // The very first page may already be on its way from before React mounted.
  const firstLoad = useRef(true);
  const loadFirstPage = useCallback(
    (signal: AbortSignal): Promise<Page<EmailSummaryDto>> => {
      const first = firstLoad.current;
      firstLoad.current = false;
      // The prefetch asked for the inbox, so it's no use to the archived list.
      if (first && !hasFilters && !showingArchived) {
        const prefetched = takePrefetchedInbox();
        // One that failed — or was started before this browser had a session
        // — simply falls back to asking again.
        if (prefetched) return prefetched.catch(() => fetchPage(0, PAGE_SIZE, signal));
      }
      return fetchPage(0, PAGE_SIZE, signal);
    },
    [fetchPage, hasFilters, showingArchived]
  );

  useEffect(() => {
    if (!connected) return;
    const controller = new AbortController();
    const id = ++requestId.current;
    // Keep the current list on screen (dimmed) while refetching, rather
    // than flashing skeletons on every filter change.
    if (emailsRef.current.length > 0) setRefetching(true);
    else setPhase('loading');
    setPollExhausted(false);

    loadFirstPage(controller.signal)
      .then((data) => {
        if (id !== requestId.current) return;
        setEmails(keepVisible(data.content));
        setTotal(data.totalElements);
        setPage(0);
        setHasMore(!data.last);
        setError(null);
        setPhase('ready');
      })
      .catch((err) => {
        if (isAbortError(err) || id !== requestId.current) return;
        setError(errorMessage(err, 'Could not load your inbox.'));
        setPhase('error');
      })
      .finally(() => {
        if (id !== requestId.current) return;
        setRefetching(false);
        if (reconcileAfterLoad.current) {
          reconcileAfterLoad.current = false;
          reconcileRef.current();
        }
      });
    return () => controller.abort();
  }, [connected, loadFirstPage, reloadKey]);

  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore || phase !== 'ready') return;
    const id = requestId.current;
    setLoadingMore(true);
    try {
      const data = await fetchPage(page + 1, PAGE_SIZE);
      if (id !== requestId.current) return;
      setEmails((prev) => {
        const seen = new Set(prev.map((e) => e.id));
        return [...prev, ...keepVisible(data.content).filter((e) => !seen.has(e.id))];
      });
      setPage((p) => p + 1);
      setHasMore(!data.last);
      setTotal(data.totalElements);
    } catch (err) {
      toast.error('Could not load more emails', errorMessage(err, 'Please try again.'));
    } finally {
      setLoadingMore(false);
    }
  }, [fetchPage, hasMore, loadingMore, page, phase, toast]);

  // Infinite scroll: load the next page as the end of the list comes into view.
  const listRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || !hasMore) return;
    const observer = new IntersectionObserver((entries) => entries[0]?.isIntersecting && loadMore(), {
      root: listRef.current,
      rootMargin: '240px',
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, loadMore]);

  const realtime = useRealtime();
  const live = realtime.status === 'open';
  const liveRef = useRef(live);
  liveRef.current = live;

  // Fallback for when the realtime stream is down: emails are analyzed in
  // the background, so while any are pending, quietly refresh so summaries
  // appear as they land. With the stream up, each result is pushed instead.
  const anyPending = phase === 'ready' && !live && !pollExhausted && emails.some(isAnalysisPending);
  useEffect(() => {
    if (!anyPending) return;
    let attempts = 0;
    const intervalId = window.setInterval(async () => {
      attempts += 1;
      if (attempts > LIST_POLL_LIMIT) {
        window.clearInterval(intervalId);
        setPollExhausted(true);
        return;
      }
      const id = requestId.current;
      try {
        const size = Math.min(100, Math.max(PAGE_SIZE, emailsRef.current.length));
        const data = await fetchPage(0, size);
        if (id !== requestId.current) return;
        setEmails((prev) => {
          const fresh = new Map(data.content.map((e) => [e.id, e]));
          const merged = prev.map((e) => fresh.get(e.id) ?? e);
          const known = new Set(prev.map((e) => e.id));
          const added = data.content.filter((e) => !known.has(e.id));
          return added.length ? [...added, ...merged] : merged;
        });
        if (!data.content.some(isAnalysisPending)) refreshStats();
      } catch {
        // Transient; the next tick retries.
      }
    }, LIST_POLL_MS);
    return () => window.clearInterval(intervalId);
  }, [anyPending, fetchPage, refreshStats]);

  // --- Sync ---
  // Syncing happens on the server, in the background: on sign-in, when the
  // app opens its realtime stream, every ~30s while it's open, and on the
  // Sync button. This page never waits for it — it shows what's stored and
  // applies what the stream reports (new mail, read/deleted changes,
  // finished analyses) one email at a time.
  const [syncing, setSyncing] = useState(false);
  const [lastSyncAt, setLastSyncAt] = useState<string | null>(null);
  const [initialSyncDone, setInitialSyncDone] = useState(true);
  const [incoming, setIncoming] = useState(0);
  const manualSync = useRef(false);

  const loadSyncStatus = useCallback(() => {
    GmailApi.status()
      .then((status) => {
        setLastSyncAt(status.lastSyncAt);
        setInitialSyncDone(status.initialSyncCompleted);
        setSyncing(status.syncing && (!status.initialSyncCompleted || manualSync.current));
      })
      .catch(() => {
        // Only feeds the subtitle; the list has its own error handling.
      });
  }, []);

  useEffect(() => {
    if (connected) loadSyncStatus();
  }, [connected, loadSyncStatus]);

  /** Quietly re-reads the first page and merges it, so the list is right even if events were missed. */
  const reconcile = useCallback(async () => {
    const id = requestId.current;
    try {
      const size = Math.min(100, Math.max(PAGE_SIZE, emailsRef.current.length));
      const data = await fetchPage(0, size);
      if (id !== requestId.current) return;
      setEmails((prev) => mergeFirstPage(prev, keepVisible(data.content), data.last));
      setTotal(data.totalElements);
      if (data.last) setHasMore(false);
      setError(null);
      setPhase('ready');
    } catch {
      // The next event, sync or reload will bring it back in line.
    }
  }, [fetchPage]);
  reconcileRef.current = reconcile;

  const finishManualSync = useCallback(
    (result: { newEmails: number } | null, failure?: string) => {
      if (!manualSync.current) return;
      manualSync.current = false;
      if (failure) toast.error('Sync failed', failure);
      else if (result && result.newEmails > 0)
        toast.success(`${pluralize(result.newEmails, 'new email')}`, 'Summaries appear as each one is analyzed.');
      else toast.info('You’re up to date', 'No new emails since the last sync.');
    },
    [toast]
  );

  /** Without the realtime stream, follow a manual sync by polling its status instead. */
  const followSyncWithoutRealtime = useCallback(async () => {
    for (let i = 0; i < SYNC_FOLLOW_LIMIT; i += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, SYNC_FOLLOW_MS));
      if (liveRef.current) return; // the stream is back and will report the result
      try {
        const status = await GmailApi.status();
        if (!status.syncing) {
          setLastSyncAt(status.lastSyncAt);
          setInitialSyncDone(status.initialSyncCompleted);
          break;
        }
      } catch {
        // Keep waiting.
      }
    }
    setSyncing(false);
    await reconcile();
    refreshStats();
    finishManualSync(null);
  }, [finishManualSync, reconcile, refreshStats]);

  const sync = useCallback(async () => {
    manualSync.current = true;
    setSyncing(true);
    try {
      await GmailApi.sync(); // returns at once; the result arrives as sync.completed
      if (!liveRef.current) await followSyncWithoutRealtime();
    } catch (err) {
      manualSync.current = false;
      setSyncing(false);
      toast.error('Sync failed', errorMessage(err, 'Please try again.'));
    }
  }, [followSyncWithoutRealtime, toast]);

  useRealtimeEvent('sync.started', (event) => {
    if (event.initial || manualSync.current) setSyncing(true);
  });
  useRealtimeEvent('sync.completed', (event) => {
    setSyncing(false);
    setIncoming(0);
    setLastSyncAt(event.lastSyncAt);
    setInitialSyncDone(true);
    finishManualSync(event);
    if (event.newEmails || event.updatedEmails || event.removedEmails || event.mode !== 'INCREMENTAL') {
      reconcile();
    }
  });
  useRealtimeEvent('sync.error', (event) => {
    setSyncing(false);
    setIncoming(0);
    // Background checks retry by themselves; only a click deserves a toast.
    // (Reconnect-required is shown as a banner by the app layout.)
    finishManualSync(null, event.message);
  });
  useRealtimeEvent('email.received', () => setIncoming((n) => n + 1));
  useRealtimeEvent('email.saved', ({ email }) => {
    setIncoming((n) => Math.max(0, n - 1));
    // Filtered views catch up on sync.completed (a new email has no
    // category or priority yet, so it rarely matches a filter anyway).
    if (hasFilters || phase !== 'ready' || emailsRef.current.some((e) => e.id === email.id)) return;
    setEmails((prev) => [email, ...prev.filter((e) => e.id !== email.id)].sort(byNewest));
    setTotal((t) => t + 1);
  });
  useRealtimeEvent('email.updated', ({ id, read, archived }) => {
    // Archived somewhere else (Gmail, another tab): it belongs to the other
    // list now, so it leaves this one.
    if (archived !== undefined && archived !== showingArchived) {
      if (!emailsRef.current.some((e) => e.id === id)) return;
      setEmails((prev) => prev.filter((e) => e.id !== id));
      setTotal((t) => Math.max(0, t - 1));
      if (selectedId === id) select(null);
      return;
    }
    setEmails((prev) => prev.map((e) => (e.id === id && e.read !== read ? { ...e, read } : e)));
  });
  useRealtimeEvent('email.deleted', ({ id }) => {
    if (!emailsRef.current.some((e) => e.id === id)) return;
    setEmails((prev) => prev.filter((e) => e.id !== id));
    setTotal((t) => Math.max(0, t - 1));
    if (selectedId === id) select(null);
  });
  useRealtimeEvent('email.analysis.started', ({ emailId }) => {
    setEmails((prev) =>
      prev.map((e) =>
        e.id === emailId && e.analysis?.analysisStatus !== 'PENDING'
          ? { ...e, analysis: { ...(e.analysis ?? emptyAnalysis), analysisStatus: 'PENDING' } }
          : e
      )
    );
  });
  useRealtimeEvent('email.analysis.completed', ({ emailId, analysis }) => {
    if (analysis) setEmails((prev) => prev.map((e) => (e.id === emailId ? { ...e, analysis } : e)));
  });
  useRealtimeEvent('email.analysis.failed', ({ emailId, analysis }) => {
    const shown = analysis ?? emptyAnalysis;
    setEmails((prev) => prev.map((e) => (e.id === emailId ? { ...e, analysis: shown } : e)));
  });
  // The stream (re)opened: whatever happened before or in between, catch up.
  // If the list is still loading, its snapshot may predate the stream, so
  // re-check once it's in.
  useRealtimeEvent('resync', () => {
    setPollExhausted(false);
    loadSyncStatus();
    if (phase === 'loading' || refetching) reconcileAfterLoad.current = true;
    else reconcile();
  });

  // Just back from Google's consent screen. The server has already started
  // fetching the newest emails; they appear here as they're saved.
  const handledConnect = useRef(false);
  useEffect(() => {
    if (!connected || handledConnect.current || params.get('connected') !== '1') return;
    handledConnect.current = true;
    updateParams({ connected: null });
    toast.success('Gmail connected', 'Fetching your latest emails…');
  }, [connected, params, toast, updateParams]);

  // --- Selection & keyboard ---
  const select = useCallback(
    (id: string | null) => updateParams({ email: id }, !(id && !selectedId)),
    [selectedId, updateParams]
  );

  const move = useCallback(
    (delta: number) => {
      const list = emailsRef.current;
      if (list.length === 0) return;
      const index = selectedId ? list.findIndex((e) => e.id === selectedId) : -1;
      const nextIndex = index === -1 ? 0 : Math.min(list.length - 1, Math.max(0, index + delta));
      const next = list[nextIndex];
      if (!next || next.id === selectedId) return;
      updateParams({ email: next.id });
      document.querySelector(`[data-email-id="${next.id}"]`)?.scrollIntoView({ block: 'nearest' });
    },
    [selectedId, updateParams]
  );

  // --- Checked rows (delete several at once) ---
  const [checkedIds, setCheckedIds] = useState<ReadonlySet<string>>(() => new Set());
  const checkedCount = checkedIds.size;

  const toggleChecked = useCallback((id: string) => {
    setCheckedIds((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);

  /** Decides which way the one Mark button goes, the way Gmail's does. */
  const allCheckedAreRead = checkedCount > 0 && emails.every((e) => !checkedIds.has(e.id) || e.read);

  const clearChecked = useCallback(() => setCheckedIds(new Set()), []);
  const checkAllLoaded = useCallback(() => setCheckedIds(new Set(emailsRef.current.map((e) => e.id))), []);

  // Rows that left the list (deleted elsewhere, filtered out) shouldn't stay
  // checked invisibly — the count would stop matching what's on screen.
  useEffect(() => {
    setCheckedIds((prev) => {
      if (prev.size === 0) return prev;
      const visible = new Set(emails.map((e) => e.id));
      const next = new Set([...prev].filter((id) => visible.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [emails]);

  const searchRef = useRef<HTMLInputElement>(null);
  useHotkeys(
    {
      '/': () => searchRef.current?.focus(),
      j: () => move(1),
      k: () => move(-1),
      x: () => selectedId && toggleChecked(selectedId),
      e: () => selectedId && archiveOne(selectedId),
      Escape: () => {
        if (checkedCount > 0) clearChecked();
        else if (selectedId) select(null);
      },
    },
    connected
  );

  const handleLoaded = useCallback(
    (detail: EmailDetailDto) => {
      const wasUnread = emailsRef.current.some((e) => e.id === detail.id && !e.read);
      if (!wasUnread) return;
      setEmails((prev) => prev.map((e) => (e.id === detail.id ? { ...e, read: true } : e)));
      refreshStats();
    },
    [refreshStats]
  );

  const handleAnalysisChange = useCallback((id: string, analysis: EmailAnalysisDto) => {
    setEmails((prev) => prev.map((e) => (e.id === id ? { ...e, analysis } : e)));
  }, []);

  // The row behind the open email, so the reader can paint before its own
  // request comes back.
  const selectedRow = useMemo(
    () => (selectedId ? emails.find((e) => e.id === selectedId) ?? null : null),
    [emails, selectedId]
  );

  // --- Delete ---
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  const pendingDelete = pendingDeleteId ? emails.find((e) => e.id === pendingDeleteId) : undefined;

  const hideRows = useCallback((ids: string[]) => {
    ids.forEach((id) => leaving.current.add(id));
    const going = new Set(ids);
    setEmails((prev) => prev.filter((e) => !going.has(e.id)));
    setTotal((t) => Math.max(0, t - ids.length));
  }, []);

  const settleRows = useCallback((ids: string[]) => ids.forEach((id) => leaving.current.delete(id)), []);

  const restoreRows = useCallback((rows: EmailSummaryDto[]) => {
    rows.forEach((row) => leaving.current.delete(row.id));
    if (rows.length === 0) return;
    setEmails((prev) => {
      const here = new Set(prev.map((e) => e.id));
      return [...prev, ...rows.filter((row) => !here.has(row.id))].sort(byNewest);
    });
    setTotal((t) => t + rows.length);
  }, []);

  const confirmDelete = async () => {
    if (!pendingDeleteId) return;
    const id = pendingDeleteId;
    const row = emails.find((e) => e.id === id);
    setPendingDeleteId(null);
    hideRows([id]);
    if (selectedId === id) select(null);
    try {
      await EmailApi.delete(id);
      settleRows([id]);
      toast.success('Email deleted', 'It was also moved to Trash in Gmail.');
      refreshStats();
    } catch (err) {
      restoreRows(row ? [row] : []);
      settleRows([id]);
      toast.error('Could not delete that email', errorMessage(err, 'It’s back in your inbox — please try again.'));
    }
  };

  // --- Acting on a selection ---
  const [confirmBulk, setConfirmBulk] = useState(false);

  /**
   * Delete and archive both take rows off the list, so they share this: the
   * rows go at once, the requests run behind them, and anything the server
   * couldn't apply comes back where it was.
   */
  const runRemovingAction = useCallback(async (
    ids: string[],
    call: (batch: string[]) => Promise<BulkActionResultDto>,
    verb: string,
    inGmail: string
  ) => {
    if (ids.length === 0) return;
    const going = new Set(ids);
    // Read through refs, not render state, so this stays stable enough to
    // hand to a memoized row without going stale.
    const rows = emailsRef.current.filter((e) => going.has(e.id)); // kept in case any have to come back

    clearChecked();
    hideRows(ids);
    if (selectedIdRef.current && going.has(selectedIdRef.current)) updateParams({ email: null });

    const applied = new Set<string>();
    let changedInGmail = 0;
    try {
      // The backend takes a bounded number per request; a big selection goes
      // in runs, and each finished run counts even if a later one fails.
      for (let i = 0; i < ids.length; i += BULK_ACTION_BATCH) {
        const result = await call(ids.slice(i, i + BULK_ACTION_BATCH));
        result.appliedIds.forEach((id) => applied.add(id));
        changedInGmail += result.changedInGmail;
      }
    } catch {
      // Whatever didn't make it comes back below, which says it better than
      // a message about the request would.
    }

    settleRows([...applied]);
    const back = rows.filter((row) => !applied.has(row.id));
    restoreRows(back);

    if (applied.size > 0) refreshStats();
    if (back.length > 0) {
      toast.error(
        `${pluralize(back.length, 'email')} couldn’t be ${verb}`,
        applied.size > 0
          ? `The other ${applied.size} went through. The rest are back in your list.`
          : 'They’re back in your list — please try again.'
      );
    } else if (applied.size > 0) {
      toast.success(`${pluralize(applied.size, 'email')} ${verb}`, gmailOutcome(applied.size, changedInGmail, inGmail));
    }
  }, [clearChecked, hideRows, settleRows, restoreRows, refreshStats, toast, updateParams]);

  const confirmBulkDelete = async () => {
    setConfirmBulk(false);
    await runRemovingAction([...checkedIds], EmailApi.bulkDelete, 'deleted', 'moved to Trash in Gmail');
  };

  /** Archiving isn't destructive, so unlike delete it doesn't ask first. */
  const archive = useCallback(
    (ids: string[], archived: boolean) =>
      runRemovingAction(
        ids,
        (batch) => EmailApi.bulkArchive(batch, archived),
        archived ? 'archived' : 'moved to your inbox',
        archived ? 'taken out of your Gmail inbox' : 'put back in your Gmail inbox'
      ),
    [runRemovingAction]
  );

  const archiveChecked = () => archive([...checkedIds], !showingArchived);
  const archiveOne = useCallback(
    (id: string) => archive([id], !emailsRef.current.find((e) => e.id === id)?.archived),
    [archive]
  );

  /** Read state doesn't remove rows — it flips a flag, and flips it back if the server refuses. */
  const markChecked = async (read: boolean) => {
    const ids = [...checkedIds];
    if (ids.length === 0) return;
    const going = new Set(ids);
    const before = new Map(emails.filter((e) => going.has(e.id)).map((e) => [e.id, e.read]));

    clearChecked();
    setEmails((prev) => prev.map((e) => (going.has(e.id) ? { ...e, read } : e)));

    const applied = new Set<string>();
    let changedInGmail = 0;
    try {
      for (let i = 0; i < ids.length; i += BULK_ACTION_BATCH) {
        const result = await EmailApi.bulkSetRead(ids.slice(i, i + BULK_ACTION_BATCH), read);
        result.appliedIds.forEach((id) => applied.add(id));
        changedInGmail += result.changedInGmail;
      }
    } catch {
      // Put back below.
    }

    const reverted = [...before.keys()].filter((id) => !applied.has(id));
    if (reverted.length > 0) {
      setEmails((prev) => prev.map((e) => (before.has(e.id) && !applied.has(e.id) ? { ...e, read: before.get(e.id)! } : e)));
      toast.error(
        `${pluralize(reverted.length, 'email')} couldn’t be marked`,
        'They’re back as they were — please try again.'
      );
    } else if (applied.size > 0) {
      const state = read ? 'read' : 'unread';
      toast.success(
        `${pluralize(applied.size, 'email')} marked ${state}`,
        gmailOutcome(applied.size, changedInGmail, `marked ${state} in Gmail`)
      );
    }
    if (applied.size > 0) refreshStats();
  };

  const clearFilters = () => {
    setSearchInput('');
    updateParams({ q: null, category: null, priority: null, risk: null, unread: null });
  };

  if (!connected) return <ConnectGmail />;

  const subtitle = [
    phase === 'ready' ? (hasFilters ? `${total.toLocaleString()} matching` : pluralize(total, 'email')) : null,
    !hasFilters && stats ? `${stats.unreadEmails.toLocaleString()} unread` : null,
    incoming > 0 ? `receiving ${incoming}…` : lastSyncAt ? `synced ${formatTimeAgo(new Date(lastSyncAt))}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
  const firstSyncRunning = !hasFilters && (syncing || !initialSyncDone);

  return (
    <div className="flex min-h-0 flex-1">
      {/* ---- List column ---- */}
      <section
        aria-label="Email list"
        className={cn(
          'min-w-0 flex-col border-r border-white/[0.06] bg-ink-850 md:flex md:w-[380px] md:shrink-0 xl:w-[420px]',
          selectedId ? 'hidden' : 'flex w-full'
        )}
      >
        <header className="shrink-0 space-y-3 border-b border-white/[0.06] px-4 pb-3 pt-4">
          {checkedCount > 0 ? (
            <div className="flex h-[38px] items-center gap-2 animate-fade-in">
              <IconButton label="Clear selection" size="sm" onClick={clearChecked}>
                <XIcon className="h-4 w-4" />
              </IconButton>
              {/* The count never truncates; the "select all" link gives way first. */}
              <p className="flex min-w-0 flex-1 items-baseline gap-2.5 text-sm font-semibold text-white" aria-live="polite">
                <span className="shrink-0">{checkedCount} selected</span>
                {checkedCount < emails.length && (
                  <button
                    type="button"
                    onClick={checkAllLoaded}
                    className="truncate text-xs font-medium text-accent-300 transition hover:text-accent-200"
                  >
                    Select all {emails.length}
                  </button>
                )}
              </p>
              <IconButton
                label={allCheckedAreRead ? 'Mark as unread' : 'Mark as read'}
                size="sm"
                onClick={() => markChecked(!allCheckedAreRead)}
              >
                {allCheckedAreRead ? <MailIcon className="h-4 w-4" /> : <MailOpenIcon className="h-4 w-4" />}
              </IconButton>
              <IconButton
                label={showingArchived ? 'Move back to the inbox' : 'Archive'}
                size="sm"
                onClick={archiveChecked}
              >
                {showingArchived ? <InboxIcon className="h-4 w-4" /> : <ArchiveIcon className="h-4 w-4" />}
              </IconButton>
              <Button
                variant="danger"
                size="sm"
                onClick={() => setConfirmBulk(true)}
                icon={<TrashIcon className="h-3.5 w-3.5" />}
              >
                Delete
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <h1 className="flex items-center gap-2 text-lg font-semibold tracking-tight text-white">
                  {showingArchived ? 'Archived' : 'Inbox'}
                  <LiveIndicator status={realtime.status} />
                </h1>
                <p className="truncate text-xs text-white/40" aria-live="polite">
                  {subtitle || '\u00a0'}
                </p>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={sync}
                loading={syncing}
                icon={<RefreshIcon className="h-3.5 w-3.5" />}
                title="Fetch new mail from Gmail"
              >
                {syncing ? 'Syncing' : 'Sync'}
              </Button>
            </div>
          )}

          <div className="relative">
            <SearchIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/30" />
            <input
              ref={searchRef}
              type="search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  if (searchInput) setSearchInput('');
                  else event.currentTarget.blur();
                }
              }}
              placeholder="Search subject, sender or text"
              aria-label="Search emails"
              className="field h-9 pl-9 pr-16 [&::-webkit-search-cancel-button]:hidden"
            />
            {searchInput ? (
              <button
                type="button"
                onClick={() => {
                  setSearchInput('');
                  searchRef.current?.focus();
                }}
                aria-label="Clear search"
                className="absolute right-2 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-md text-white/40 hover:bg-white/10 hover:text-white/70"
              >
                <XIcon className="h-3.5 w-3.5" />
              </button>
            ) : (
              <kbd className="kbd pointer-events-none absolute right-2.5 top-1/2 hidden -translate-y-1/2 md:inline-flex">/</kbd>
            )}
          </div>

          <div className="-mx-4 flex items-center gap-1.5 overflow-x-auto px-4 scrollbar-none" role="group" aria-label="Filters">
            <button
              type="button"
              aria-pressed={unreadOnly}
              onClick={() => updateParams({ unread: unreadOnly ? null : '1' })}
              className={cn(
                'h-7 shrink-0 rounded-full border px-3 text-xs font-medium transition',
                unreadOnly
                  ? 'border-accent-500/40 bg-accent-500/10 text-accent-200'
                  : 'border-white/10 text-white/55 hover:border-white/20 hover:text-white/80'
              )}
            >
              Unread
            </button>
            {/* A different list, not a filter within the inbox — so it clears
                the selection and sends you back to the top. */}
            <button
              type="button"
              aria-pressed={showingArchived}
              onClick={() => {
                clearChecked();
                updateParams({ archived: showingArchived ? null : '1', email: null });
              }}
              className={cn(
                'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-full border px-3 text-xs font-medium transition',
                showingArchived
                  ? 'border-accent-500/40 bg-accent-500/10 text-accent-200'
                  : 'border-white/10 text-white/55 hover:border-white/20 hover:text-white/80'
              )}
            >
              <ArchiveIcon className="h-3.5 w-3.5" />
              Archived
            </button>
            <FilterSelect value={priority} onChange={(v) => updateParams({ priority: v || null })} label="Filter by priority">
              <option value="">Priority</option>
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {titleCase(p)} priority
                </option>
              ))}
            </FilterSelect>
            <FilterSelect value={riskLevel} onChange={(v) => updateParams({ risk: v || null })} label="Filter by risk">
              <option value="">Risk</option>
              <option value="HIGH">Possible high risk</option>
              <option value="MEDIUM">Possible risk</option>
              <option value="LOW">Low risk</option>
            </FilterSelect>
            <FilterSelect value={category} onChange={(v) => updateParams({ category: v || null })} label="Filter by category">
              <option value="">Category</option>
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {titleCase(c)}
                </option>
              ))}
            </FilterSelect>
            {hasFilters && (
              <button
                type="button"
                onClick={clearFilters}
                className="h-7 shrink-0 rounded-full px-2.5 text-xs font-medium text-white/45 transition hover:bg-white/[0.06] hover:text-white/80"
              >
                Clear
              </button>
            )}
          </div>
        </header>

        <div
          ref={listRef}
          className={cn('relative flex-1 overflow-y-auto scrollbar-thin transition-opacity', refetching && 'opacity-60')}
          aria-busy={phase === 'loading' || refetching}
        >
          {phase === 'loading' && <EmailListSkeleton />}

          {phase === 'error' && (
            <div className="p-4">
              <Alert
                tone="danger"
                title="Your inbox couldn’t be loaded"
                action={
                  <Button size="sm" variant="secondary" onClick={() => setReloadKey((k) => k + 1)}>
                    Retry
                  </Button>
                }
              >
                {error}
              </Alert>
            </div>
          )}

          {phase === 'ready' && emails.length === 0 && (
            firstSyncRunning ? (
              <EmptyState
                icon={<Spinner className="h-5 w-5 text-accent-400" />}
                title="Fetching your latest emails"
                description="Your most recent Gmail messages will appear here in a moment, each one summarized as soon as it’s analyzed."
              />
            ) : hasFilters ? (
              <EmptyState
                icon={<SearchIcon className="h-5 w-5" />}
                title="No emails match"
                description="Try a different search, or clear your filters to see everything."
                action={
                  <Button size="sm" variant="secondary" onClick={clearFilters}>
                    Clear filters
                  </Button>
                }
              />
            ) : showingArchived ? (
              <EmptyState
                icon={<ArchiveIcon className="h-5 w-5" />}
                title="Nothing archived"
                description="Archiving takes an email out of your inbox — here and in Gmail — without deleting it. Anything you file away shows up here."
              />
            ) : (
              <EmptyState
                icon={<InboxIcon className="h-5 w-5" />}
                title="Nothing here yet"
                description="Sync to pull in your latest Gmail messages. InboxIQ will summarize and prioritize each one."
                action={
                  <Button variant="primary" onClick={sync} loading={syncing} icon={<RefreshIcon className="h-4 w-4" />}>
                    Sync Gmail
                  </Button>
                }
              />
            )
          )}

          {phase === 'ready' && emails.length > 0 && (
            <>
              <ul>
                {emails.map((email, index) => {
                  const day = listDayLabel(email.receivedAt);
                  const startsDay = index === 0 || day !== listDayLabel(emails[index - 1].receivedAt);
                  return (
                    <Fragment key={email.id}>
                      {/* Sticky, so you always know how far back you've
                          scrolled without reading timestamps. */}
                      {startsDay && (
                        <li
                          className="eyebrow sticky top-0 z-10 border-b border-white/[0.04] bg-ink-850/85 px-4 py-1.5 backdrop-blur"
                          aria-hidden="true"
                        >
                          {day}
                        </li>
                      )}
                      <EmailListItem
                        email={pollExhausted && isAnalysisPending(email) ? { ...email, analysis: { ...emptyAnalysis } } : email}
                        active={email.id === selectedId}
                        selected={checkedIds.has(email.id)}
                        selecting={checkedCount > 0}
                        onSelect={select}
                        onToggleSelected={toggleChecked}
                        onArchive={archiveOne}
                        onDelete={setPendingDeleteId}
                      />
                    </Fragment>
                  );
                })}
              </ul>
              <div ref={sentinelRef} className="flex h-16 items-center justify-center text-xs text-white/30">
                {loadingMore ? (
                  <Spinner className="h-4 w-4 text-white/40" />
                ) : hasMore ? (
                  <button type="button" onClick={loadMore} className="rounded-md px-2 py-1 hover:text-white/60">
                    Load more
                  </button>
                ) : (
                  emails.length > 10 && 'That’s everything'
                )}
              </div>
            </>
          )}
        </div>
      </section>

      {/* ---- Reader column ---- */}
      <section
        aria-label="Email"
        className={cn('min-w-0 flex-1 flex-col bg-ink-900', selectedId ? 'flex' : 'hidden md:flex')}
      >
        {selectedId ? (
          <EmailDetailPane
            emailId={selectedId}
            preview={selectedRow}
            onClose={() => select(null)}
            onRequestDelete={setPendingDeleteId}
            onArchive={archiveOne}
            onLoaded={handleLoaded}
            onAnalysisChange={handleAnalysisChange}
          />
        ) : (
          <ReaderStartHere
            stats={stats}
            onPick={(filter) =>
              updateParams({
                unread: filter === 'unread' ? '1' : null,
                priority: filter === 'priority' ? 'HIGH' : null,
                risk: filter === 'risk' ? 'HIGH' : null,
              })
            }
          />
        )}
      </section>

      <ConfirmDialog
        open={pendingDeleteId !== null}
        onClose={() => setPendingDeleteId(null)}
        onConfirm={confirmDelete}
        title="Delete this email?"
        description={
          <>
            {pendingDelete ? (
              <>
                “{pendingDelete.subject || '(no subject)'}” will be removed from InboxIQ and{' '}
              </>
            ) : (
              'This email will be removed from InboxIQ and '
            )}
            moved to Trash in your Gmail, where you can still restore it for 30 days.
          </>
        }
        confirmLabel="Delete email"
      />
      <ConfirmDialog
        open={confirmBulk}
        onClose={() => setConfirmBulk(false)}
        onConfirm={confirmBulkDelete}
        title={`Delete ${pluralize(checkedCount, 'email')}?`}
        description={`${
          checkedCount === 1 ? 'It' : 'They'
        } will be removed from InboxIQ and moved to Trash in your Gmail, where you can still restore ${
          checkedCount === 1 ? 'it' : 'them'
        } for 30 days.`}
        confirmLabel={`Delete ${checkedCount}`}
      />
    </div>
  );
}

// Stand-in for an analysis that failed without a result (or that fallback
// polling gave up on), so the row shows its snippet instead of a perpetual
// "Analyzing…". Also the base for marking a row as analyzing.
const emptyAnalysis: EmailAnalysisDto = {
  summary: null,
  keyPoints: [],
  category: null,
  priority: null,
  priorityScore: null,
  riskScore: null,
  riskLevel: null,
  riskReasons: [],
  requiresReply: null,
  actionRequired: null,
  importantDates: [],
  analysisStatus: 'FAILED',
  failureReason: null,
};
