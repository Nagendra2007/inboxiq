import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { EmailApi, GmailApi, type SearchFilters } from '../api/endpoints';
import { errorMessage, isAbortError, startGoogleLogin } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useAppShell } from '../context/AppShell';
import type { Category, EmailAnalysisDto, EmailDetailDto, EmailSummaryDto, Page, Priority, RiskLevel } from '../types';
import { cn } from '../lib/cn';
import { formatTimeAgo, pluralize, titleCase } from '../lib/format';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useHotkeys } from '../hooks/useHotkeys';
import { EmailListItem, EmailListSkeleton, isAnalysisPending } from '../components/EmailListItem';
import { EmailDetailPane } from '../components/EmailDetailPane';
import { Button } from '../components/ui/Button';
import { ConfirmDialog } from '../components/ui/Dialog';
import { Alert, EmptyState, Spinner } from '../components/ui/Feedback';
import { useToast } from '../components/ui/Toast';
import {
  ChevronDownIcon,
  GoogleIcon,
  InboxIcon,
  LockIcon,
  MailIcon,
  RefreshIcon,
  SearchIcon,
  SendIcon,
  ShieldCheckIcon,
  SparklesIcon,
  XIcon,
} from '../components/ui/Icons';

const CATEGORIES: Category[] = ['PERSONAL', 'WORK', 'EDUCATION', 'FINANCE', 'SHOPPING', 'DELIVERY', 'SECURITY', 'SOCIAL', 'MARKETING', 'NEWSLETTER', 'SUSPICIOUS', 'OTHER'];
const PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];
const RISK_LEVELS: RiskLevel[] = ['HIGH', 'MEDIUM', 'LOW'];

const PAGE_SIZE = 40;
const LIST_POLL_MS = 5000;
const LIST_POLL_LIMIT = 24; // ~2 minutes of watching for background analysis

function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | '' {
  return value && (allowed as readonly string[]).includes(value) ? (value as T) : '';
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
          onClick={startGoogleLogin}
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

function ReaderEmptyState() {
  return (
    <div className="flex h-full flex-col items-center justify-center px-8 text-center">
      <div className="mb-5 flex h-14 w-14 items-center justify-center rounded-2xl border border-white/[0.06] bg-white/[0.02] text-white/30">
        <InboxIcon className="h-6 w-6" />
      </div>
      <p className="text-sm font-semibold text-white/75">Select an email to read</p>
      <p className="mt-1.5 max-w-xs text-sm leading-relaxed text-white/40">
        InboxIQ summarizes each message, flags possible risks, and drafts replies for you to approve.
      </p>
      <div className="mt-6 flex flex-wrap items-center justify-center gap-x-4 gap-y-2 text-xs text-white/35">
        <span className="flex items-center gap-1.5">
          <kbd className="kbd">J</kbd>
          <kbd className="kbd">K</kbd> navigate
        </span>
        <span className="flex items-center gap-1.5">
          <kbd className="kbd">/</kbd> search
        </span>
        <span className="flex items-center gap-1.5">
          <kbd className="kbd">C</kbd> compose
        </span>
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
    }),
    [q, category, priority, riskLevel, unreadOnly]
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

  const fetchPage = useCallback(
    (pageIndex: number, size: number, signal?: AbortSignal): Promise<Page<EmailSummaryDto>> =>
      hasFilters ? EmailApi.search(filters, pageIndex, size, signal) : EmailApi.list(pageIndex, size, signal),
    [filters, hasFilters]
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

    fetchPage(0, PAGE_SIZE, controller.signal)
      .then((data) => {
        if (id !== requestId.current) return;
        setEmails(data.content);
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
        if (id === requestId.current) setRefetching(false);
      });
    return () => controller.abort();
  }, [connected, fetchPage, reloadKey]);

  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore || phase !== 'ready') return;
    const id = requestId.current;
    setLoadingMore(true);
    try {
      const data = await fetchPage(page + 1, PAGE_SIZE);
      if (id !== requestId.current) return;
      setEmails((prev) => {
        const seen = new Set(prev.map((e) => e.id));
        return [...prev, ...data.content.filter((e) => !seen.has(e.id))];
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

  // Freshly synced emails are analyzed in the background. While any are
  // still pending, quietly refresh so summaries appear as they land.
  const anyPending = phase === 'ready' && !pollExhausted && emails.some(isAnalysisPending);
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
  const [syncing, setSyncing] = useState(false);
  const [lastSynced, setLastSynced] = useState<Date | null>(null);

  const sync = useCallback(async () => {
    setSyncing(true);
    try {
      const result = await GmailApi.sync();
      setLastSynced(new Date());
      if (result.newlyStored > 0) {
        toast.success(`${pluralize(result.newlyStored, 'new email')} synced`, 'AI summaries appear as each one is analyzed.');
      } else {
        toast.info('You’re up to date', 'No new emails since the last sync.');
      }
      setReloadKey((k) => k + 1);
      refreshStats();
    } catch (err) {
      toast.error('Sync failed', errorMessage(err, 'Please try again.'));
    } finally {
      setSyncing(false);
    }
  }, [refreshStats, toast]);

  // Just back from Google's consent screen: pull the inbox straight away.
  const handledConnect = useRef(false);
  useEffect(() => {
    if (!connected || handledConnect.current || params.get('connected') !== '1') return;
    handledConnect.current = true;
    updateParams({ connected: null });
    toast.success('Gmail connected', 'Fetching your latest emails…');
    sync();
  }, [connected, params, sync, toast, updateParams]);

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

  const searchRef = useRef<HTMLInputElement>(null);
  useHotkeys(
    {
      '/': () => searchRef.current?.focus(),
      j: () => move(1),
      k: () => move(-1),
      Escape: () => selectedId && select(null),
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

  // --- Delete ---
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const pendingDelete = pendingDeleteId ? emails.find((e) => e.id === pendingDeleteId) : undefined;

  const confirmDelete = async () => {
    if (!pendingDeleteId) return;
    const id = pendingDeleteId;
    setDeleting(true);
    try {
      await EmailApi.delete(id);
      setEmails((prev) => prev.filter((e) => e.id !== id));
      setTotal((t) => Math.max(0, t - 1));
      if (selectedId === id) select(null);
      toast.success('Email deleted', 'It was also moved to Trash in Gmail.');
      refreshStats();
    } catch (err) {
      toast.error('Could not delete that email', errorMessage(err, 'Please try again.'));
    } finally {
      setDeleting(false);
      setPendingDeleteId(null);
    }
  };

  const clearFilters = () => {
    setSearchInput('');
    updateParams({ q: null, category: null, priority: null, risk: null, unread: null });
  };

  if (!connected) return <ConnectGmail />;

  const subtitle = [
    phase === 'ready' ? (hasFilters ? `${total.toLocaleString()} matching` : pluralize(total, 'email')) : null,
    !hasFilters && stats ? `${stats.unreadEmails.toLocaleString()} unread` : null,
    lastSynced ? `synced ${formatTimeAgo(lastSynced)}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

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
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1">
              <h1 className="text-lg font-semibold tracking-tight text-white">Inbox</h1>
              <p className="truncate text-xs text-white/40" aria-live="polite">
                {subtitle || ' '}
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
            hasFilters ? (
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
                {emails.map((email) => (
                  <EmailListItem
                    key={email.id}
                    email={pollExhausted && isAnalysisPending(email) ? { ...email, analysis: { ...emptyAnalysis } } : email}
                    active={email.id === selectedId}
                    onSelect={select}
                    onDelete={setPendingDeleteId}
                  />
                ))}
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
            onClose={() => select(null)}
            onRequestDelete={setPendingDeleteId}
            onLoaded={handleLoaded}
            onAnalysisChange={handleAnalysisChange}
          />
        ) : (
          <ReaderEmptyState />
        )}
      </section>

      <ConfirmDialog
        open={pendingDeleteId !== null}
        onClose={() => !deleting && setPendingDeleteId(null)}
        onConfirm={confirmDelete}
        loading={deleting}
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
    </div>
  );
}

// Stand-in once polling gives up, so a row whose analysis never arrived
// shows its snippet instead of a perpetual "Summarizing…".
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
