import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { EmailApi, ActionItemApi } from '../api/endpoints';
import { errorMessage, isAbortError } from '../api/client';
import { useRealtime, useRealtimeEvent } from '../context/RealtimeContext';
import type { EmailAnalysisDto, EmailDetailDto, ThreadMessageDto } from '../types';
import { cn } from '../lib/cn';
import { deadlineInfo, formatFullDate, formatListTime, parseSender } from '../lib/format';
import { CategoryPill, PriorityBadge, RiskBadge, SignalPill } from './Badges';
import { ReplyComposer } from './ReplyComposer';
import { Avatar } from './ui/Avatar';
import { Button, IconButton } from './ui/Button';
import { Checkbox } from './ui/Checkbox';
import { Alert } from './ui/Feedback';
import { useToast } from './ui/Toast';
import {
  AlertTriangleIcon,
  CalendarIcon,
  ChevronDownIcon,
  ChevronLeftIcon,
  EyeIcon,
  RefreshIcon,
  SparklesIcon,
  ThreadIcon,
  TodoIcon,
  TrashIcon,
  XIcon,
} from './ui/Icons';

// Fallback only, while the realtime stream is down.
const ANALYSIS_POLL_MS = 3000;
const ANALYSIS_POLL_LIMIT = 40; // ~2 minutes

/** No analysis yet also counts: opening an email that was never analyzed starts its analysis. */
function isPending(analysis: EmailAnalysisDto | null | undefined): boolean {
  return !analysis || analysis.analysisStatus === 'PENDING';
}

function DetailSkeleton() {
  return (
    <div className="mx-auto w-full max-w-3xl space-y-6 px-5 py-8 md:px-8" aria-hidden="true">
      <span className="skeleton h-7 w-3/4" />
      <div className="flex gap-2">
        <span className="skeleton h-6 w-20" />
        <span className="skeleton h-6 w-28" />
      </div>
      <div className="flex items-center gap-3">
        <span className="skeleton h-11 w-11 rounded-full" />
        <div className="flex-1 space-y-2">
          <span className="skeleton h-3.5 w-40" />
          <span className="skeleton h-3 w-56" />
        </div>
      </div>
      <div className="card space-y-3 p-5">
        <span className="skeleton h-3 w-24" />
        <span className="skeleton h-3.5 w-full" />
        <span className="skeleton h-3.5 w-11/12" />
        <span className="skeleton h-3.5 w-2/3" />
      </div>
    </div>
  );
}

function SectionCard({
  icon,
  title,
  aside,
  children,
  className,
}: {
  icon: ReactNode;
  title: string;
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn('card p-5', className)}>
      <header className="mb-3 flex items-center gap-2">
        <span className="text-white/40">{icon}</span>
        <h2 className="eyebrow">{title}</h2>
        {aside && <span className="ml-auto">{aside}</span>}
      </header>
      {children}
    </section>
  );
}

function ThreadView({ emailId }: { emailId: string }) {
  const [messages, setMessages] = useState<ThreadMessageDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    EmailApi.thread(emailId)
      .then((data) => !cancelled && setMessages(data))
      .catch((err) => !cancelled && setError(errorMessage(err, 'Could not load the conversation from Gmail.')));
    return () => {
      cancelled = true;
    };
  }, [emailId]);

  if (error) return <Alert tone="danger">{error}</Alert>;
  if (!messages) {
    return (
      <div className="space-y-2" aria-hidden="true">
        {[0, 1].map((i) => (
          <span key={i} className="skeleton h-14 w-full rounded-xl" />
        ))}
      </div>
    );
  }
  if (messages.length === 0) return <p className="text-sm text-white/40">No other messages in this conversation.</p>;

  return (
    <ol className="relative space-y-2 before:absolute before:bottom-4 before:left-[15px] before:top-4 before:w-px before:bg-white/[0.06]">
      {messages.map((message) => {
        const sender = parseSender(message.sender);
        const open = expanded === message.messageId;
        return (
          <li key={message.messageId} className="relative flex gap-3">
            <Avatar name={sender.name} seed={sender.email || sender.name} size="sm" className="relative z-10 ring-4 ring-ink-800" />
            <button
              type="button"
              onClick={() => setExpanded(open ? null : message.messageId)}
              aria-expanded={open}
              className="min-w-0 flex-1 rounded-xl border border-white/[0.06] bg-white/[0.02] px-3.5 py-2.5 text-left transition hover:border-white/10"
            >
              <span className="flex items-baseline justify-between gap-3">
                <span className="truncate text-sm font-medium text-white/80">{sender.name}</span>
                <span className="shrink-0 text-xs text-white/35">{formatListTime(message.receivedAt)}</span>
              </span>
              <span className={cn('mt-1 block text-sm text-white/50', open ? 'whitespace-pre-wrap' : 'line-clamp-2')}>
                {open ? message.bodyText || message.snippet : message.snippet}
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}

export function EmailDetailPane({
  emailId,
  onClose,
  onRequestDelete,
  onLoaded,
  onAnalysisChange,
}: {
  emailId: string;
  onClose: () => void;
  onRequestDelete: (id: string) => void;
  /** Called once the email is fetched (the backend marks it read at that point). */
  onLoaded?: (email: EmailDetailDto) => void;
  onAnalysisChange?: (id: string, analysis: EmailAnalysisDto) => void;
}) {
  const toast = useToast();
  const [email, setEmail] = useState<EmailDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reanalyzing, setReanalyzing] = useState(false);
  const [showThread, setShowThread] = useState(false);
  const [showOriginal, setShowOriginal] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const callbacks = useRef({ onLoaded, onAnalysisChange });
  callbacks.current = { onLoaded, onAnalysisChange };

  const load = useCallback(
    async (signal?: AbortSignal, quiet = false) => {
      if (!quiet) {
        setLoading(true);
        setError(null);
      }
      try {
        const data = await EmailApi.get(emailId, signal);
        setEmail(data);
        callbacks.current.onLoaded?.(data);
      } catch (err) {
        if (isAbortError(err)) return;
        if (!quiet) setError(errorMessage(err, 'Could not load this email.'));
      } finally {
        if (!quiet && !signal?.aborted) setLoading(false);
      }
    },
    [emailId]
  );

  useEffect(() => {
    const controller = new AbortController();
    setEmail(null);
    setShowThread(false);
    setShowOriginal(false);
    scrollRef.current?.scrollTo({ top: 0 });
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  // Emails are analyzed in the background. The result is pushed over the
  // realtime stream; only this email is updated.
  const applyAnalysis = useCallback(
    async (analysis: EmailAnalysisDto | null | undefined) => {
      if (!analysis) return;
      setEmail((prev) => (prev ? { ...prev, analysis } : prev));
      callbacks.current.onAnalysisChange?.(emailId, analysis);
      await load(undefined, true); // picks up extracted action items too
    },
    [emailId, load]
  );
  useRealtimeEvent('email.analysis.completed', (event) => {
    if (event.emailId === emailId) applyAnalysis(event.analysis);
  });
  useRealtimeEvent('email.analysis.failed', (event) => {
    if (event.emailId === emailId) applyAnalysis(event.analysis);
  });
  useRealtimeEvent('email.analysis.started', (event) => {
    if (event.emailId !== emailId) return;
    setEmail((prev) =>
      prev && prev.analysis && prev.analysis.analysisStatus !== 'PENDING'
        ? { ...prev, analysis: { ...prev.analysis, analysisStatus: 'PENDING' } }
        : prev
    );
  });
  useRealtimeEvent('resync', () => {
    load(undefined, true);
  });

  // Fallback while the realtime stream is down: poll for the result.
  const live = useRealtime().status === 'open';
  const pending = !!email && isPending(email.analysis);
  useEffect(() => {
    if (!pending || live) return;
    let attempts = 0;
    const controller = new AbortController();
    const id = window.setInterval(async () => {
      attempts += 1;
      if (attempts > ANALYSIS_POLL_LIMIT) {
        window.clearInterval(id);
        return;
      }
      try {
        const analysis = await EmailApi.analysis(emailId, controller.signal);
        if (analysis && !isPending(analysis)) {
          window.clearInterval(id);
          callbacks.current.onAnalysisChange?.(emailId, analysis);
          await load(controller.signal, true); // picks up extracted action items too
        }
      } catch {
        // Keep polling quietly; the pane already shows a pending state.
      }
    }, ANALYSIS_POLL_MS);
    return () => {
      window.clearInterval(id);
      controller.abort();
    };
  }, [pending, live, emailId, load]);

  const reanalyze = async () => {
    setReanalyzing(true);
    try {
      const analysis = await EmailApi.reanalyze(emailId);
      setEmail((prev) => (prev ? { ...prev, analysis } : prev));
      callbacks.current.onAnalysisChange?.(emailId, analysis);
      await load(undefined, true);
      toast.success('Analysis updated');
    } catch (err) {
      toast.error('Could not re-run the analysis', errorMessage(err, 'Please try again in a moment.'));
    } finally {
      setReanalyzing(false);
    }
  };

  const toggleActionItem = async (itemId: string, completed: boolean) => {
    setEmail((prev) =>
      prev ? { ...prev, actionItems: prev.actionItems.map((it) => (it.id === itemId ? { ...it, completed } : it)) } : prev
    );
    try {
      await ActionItemApi.setCompleted(itemId, completed);
    } catch (err) {
      setEmail((prev) =>
        prev
          ? { ...prev, actionItems: prev.actionItems.map((it) => (it.id === itemId ? { ...it, completed: !completed } : it)) }
          : prev
      );
      toast.error('Could not update the to-do', errorMessage(err, 'Please try again.'));
    }
  };

  const toolbar = (
    <div className="flex h-14 shrink-0 items-center gap-1 border-b border-white/[0.06] bg-ink-900/80 px-3 backdrop-blur md:px-5">
      <Button variant="ghost" size="sm" onClick={onClose} className="md:hidden" icon={<ChevronLeftIcon className="h-4 w-4" />}>
        Inbox
      </Button>
      <div className="ml-auto flex items-center gap-1">
        {email && (
          <>
            <Button
              variant="ghost"
              size="sm"
              onClick={reanalyze}
              loading={reanalyzing}
              icon={<RefreshIcon className="h-3.5 w-3.5" />}
              title="Run the AI analysis again"
            >
              <span className="hidden sm:inline">{reanalyzing ? 'Analyzing…' : 'Re-analyze'}</span>
            </Button>
            <IconButton label="Delete email (moves it to Gmail Trash)" tone="danger" onClick={() => onRequestDelete(emailId)}>
              <TrashIcon className="h-4 w-4" />
            </IconButton>
          </>
        )}
        <IconButton label="Close (Esc)" onClick={onClose} className="hidden md:inline-flex">
          <XIcon className="h-4 w-4" />
        </IconButton>
      </div>
    </div>
  );

  if (loading) {
    return (
      <div className="flex h-full flex-col">
        {toolbar}
        <DetailSkeleton />
      </div>
    );
  }

  if (error || !email) {
    return (
      <div className="flex h-full flex-col">
        {toolbar}
        <div className="mx-auto w-full max-w-3xl px-5 py-8">
          <Alert
            tone="danger"
            title="This email couldn’t be opened"
            action={
              <Button size="sm" variant="secondary" onClick={() => load()}>
                Retry
              </Button>
            }
          >
            {error}
          </Alert>
        </div>
      </div>
    );
  }

  const analysis = email.analysis;
  const sender = parseSender(email.sender);
  const riskLevel = analysis?.riskLevel;
  const showRisk = riskLevel === 'HIGH' || riskLevel === 'MEDIUM';
  const openItems = email.actionItems.filter((i) => !i.completed).length;

  return (
    <div className="flex h-full flex-col">
      {toolbar}
      <div ref={scrollRef} className="flex-1 overflow-y-auto scrollbar-thin">
        <article className="mx-auto w-full max-w-3xl space-y-5 px-5 pb-10 pt-6 animate-fade-in md:px-8">
          <header className="space-y-4">
            <h1 className="text-xl font-semibold leading-snug tracking-tight text-white md:text-2xl">
              {email.subject || '(no subject)'}
            </h1>
            {(analysis?.category || analysis?.priority || showRisk) && (
              <div className="flex flex-wrap items-center gap-1.5">
                <PriorityBadge priority={analysis?.priority} score={analysis?.priorityScore} />
                <RiskBadge level={riskLevel} />
                <CategoryPill category={analysis?.category} />
              </div>
            )}
            <div className="flex items-center gap-3">
              <Avatar name={sender.name} seed={sender.email || sender.name} size="lg" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm">
                  <span className="font-semibold text-white/90">{sender.name}</span>
                  {sender.email && <span className="ml-1.5 text-white/40">&lt;{sender.email}&gt;</span>}
                </p>
                <p className="truncate text-xs text-white/40">
                  {email.recipient ? `to ${email.recipient}` : 'to me'}
                  {email.ccRecipient && ` · cc ${email.ccRecipient}`}
                </p>
              </div>
              <time dateTime={email.receivedAt ?? undefined} className="hidden shrink-0 text-xs text-white/40 sm:block">
                {formatFullDate(email.receivedAt)}
              </time>
            </div>
          </header>

          {showRisk && analysis && (
            <Alert tone={riskLevel === 'HIGH' ? 'danger' : 'warning'} title="This email may be worth a closer look">
              <p>
                InboxIQ flags possibilities, not certainties. Check the sender and links before you click, pay, or share
                anything.
              </p>
              {analysis.riskReasons.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {analysis.riskReasons.map((reason, i) => (
                    <li key={i} className="flex gap-2">
                      <span className="mt-2 h-1 w-1 shrink-0 rounded-full bg-current opacity-60" aria-hidden="true" />
                      {reason}
                    </li>
                  ))}
                </ul>
              )}
            </Alert>
          )}

          <SectionCard
            icon={<SparklesIcon className="h-4 w-4 text-accent-400" />}
            title="AI summary"
            aside={
              analysis?.priorityScore != null && !isPending(analysis) ? (
                <span className="text-2xs tabular-nums text-white/35" title="Priority score (0–100)">
                  Priority {analysis.priorityScore}/100
                </span>
              ) : undefined
            }
            className="border-accent-500/10 bg-gradient-to-b from-accent-500/[0.035] to-transparent"
          >
            {isPending(analysis) ? (
              <div className="space-y-2.5" role="status">
                <p className="flex items-center gap-2 text-sm text-white/50">
                  <SparklesIcon className="h-3.5 w-3.5 animate-pulse text-accent-400" />
                  Analyzing… the summary will appear here on its own.
                </p>
                <span className="skeleton h-3.5 w-full" />
                <span className="skeleton h-3.5 w-5/6" />
              </div>
            ) : analysis?.summary ? (
              <>
                <p className="text-[15px] leading-relaxed text-white/85">{analysis.summary}</p>
                {analysis.keyPoints.length > 0 && (
                  <ul className="mt-3 space-y-1.5">
                    {analysis.keyPoints.map((point, i) => (
                      <li key={i} className="flex gap-2.5 text-sm leading-relaxed text-white/65">
                        <span className="mt-[9px] h-1 w-1 shrink-0 rounded-full bg-accent-500" aria-hidden="true" />
                        {point}
                      </li>
                    ))}
                  </ul>
                )}
              </>
            ) : (
              <p className="text-sm text-white/45">No AI summary is available for this email.</p>
            )}

            {analysis?.analysisStatus === 'FAILED' && (
              <Alert
                className="mt-3"
                action={
                  <Button size="xs" variant="secondary" onClick={reanalyze} loading={reanalyzing}>
                    Retry
                  </Button>
                }
              >
                AI analysis is temporarily unavailable — showing rule-based signals only.
              </Alert>
            )}

            {analysis && !isPending(analysis) && (analysis.requiresReply || analysis.actionRequired || analysis.importantDates.length > 0) && (
              <div className="mt-4 flex flex-wrap items-center gap-1.5 border-t border-white/[0.05] pt-3.5">
                {analysis.requiresReply && <SignalPill>Needs a reply</SignalPill>}
                {analysis.actionRequired && <SignalPill>Action required</SignalPill>}
                {analysis.importantDates.map((date) => (
                  <span
                    key={date}
                    className="inline-flex h-6 items-center gap-1.5 rounded-md bg-white/[0.04] px-2 text-[11px] font-medium text-white/60 ring-1 ring-inset ring-white/10"
                  >
                    <CalendarIcon className="h-3 w-3" />
                    {date}
                  </span>
                ))}
              </div>
            )}
          </SectionCard>

          {email.actionItems.length > 0 && (
            <SectionCard
              icon={<TodoIcon className="h-4 w-4" />}
              title="Action items"
              aside={<span className="text-2xs text-white/35">{openItems} open</span>}
            >
              <ul className="space-y-1">
                {email.actionItems.map((item) => {
                  const due = deadlineInfo(item.deadline);
                  return (
                    <li key={item.id} className="flex items-start gap-3 rounded-lg px-1 py-1.5">
                      <Checkbox
                        checked={item.completed}
                        onChange={(checked) => toggleActionItem(item.id, checked)}
                        label={`Mark "${item.description}" as ${item.completed ? 'not done' : 'done'}`}
                        className="mt-0.5"
                      />
                      <span className="min-w-0 flex-1">
                        <span className={cn('block text-sm', item.completed ? 'text-white/30 line-through' : 'text-white/80')}>
                          {item.description}
                        </span>
                        {item.deadline && !item.completed && (
                          <span
                            className={cn(
                              'mt-0.5 inline-flex items-center gap-1 text-xs',
                              due.group === 'overdue' ? 'text-rose-300' : due.group === 'today' ? 'text-amber-200' : 'text-white/40'
                            )}
                          >
                            <CalendarIcon className="h-3 w-3" />
                            {due.label}
                          </span>
                        )}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </SectionCard>
          )}

          {/* The original is collapsed by default — the AI summary above is
              usually all that's needed. */}
          <section className="card overflow-hidden">
            <button
              type="button"
              onClick={() => setShowOriginal((v) => !v)}
              aria-expanded={showOriginal}
              className="flex w-full items-center gap-2 px-5 py-3.5 text-left transition hover:bg-white/[0.02]"
            >
              <EyeIcon className="h-4 w-4 text-white/40" />
              <span className="eyebrow">Original message</span>
              <ChevronDownIcon className={cn('ml-auto h-4 w-4 text-white/40 transition-transform', showOriginal && 'rotate-180')} />
            </button>
            {showOriginal && (
              <div className="border-t border-white/[0.06] p-4 animate-fade-in">
                {email.bodyHtml ? (
                  <div className="overflow-x-auto rounded-xl bg-white px-5 py-4 shadow-inner">
                    <div className="email-html" dangerouslySetInnerHTML={{ __html: email.bodyHtml }} />
                  </div>
                ) : email.bodyText ? (
                  <p className="whitespace-pre-wrap break-words px-1 text-sm leading-relaxed text-white/75">{email.bodyText}</p>
                ) : (
                  <p className="px-1 text-sm text-white/40">This message has no readable body.</p>
                )}
                {email.hasAttachments && (
                  <p className="mt-3 px-1 text-xs text-white/40">
                    This email has attachments — open it in Gmail to view them.
                  </p>
                )}
              </div>
            )}
          </section>

          {email.threadId && (
            <section className="card overflow-hidden">
              <button
                type="button"
                onClick={() => setShowThread((v) => !v)}
                aria-expanded={showThread}
                className="flex w-full items-center gap-2 px-5 py-3.5 text-left transition hover:bg-white/[0.02]"
              >
                <ThreadIcon className="h-4 w-4 text-white/40" />
                <span className="eyebrow">Conversation</span>
                <span className="ml-2 text-2xs text-white/30">Live from Gmail</span>
                <ChevronDownIcon className={cn('ml-auto h-4 w-4 text-white/40 transition-transform', showThread && 'rotate-180')} />
              </button>
              {showThread && (
                <div className="border-t border-white/[0.06] p-4 animate-fade-in">
                  <ThreadView emailId={email.id} />
                </div>
              )}
            </section>
          )}

          {riskLevel === 'HIGH' && (
            <p className="flex items-center gap-2 px-1 text-xs text-rose-200/70">
              <AlertTriangleIcon className="h-3.5 w-3.5" />
              Be careful replying to a message flagged as possibly risky.
            </p>
          )}

          <ReplyComposer key={email.id} emailId={email.id} sender={email.sender} subject={email.subject} />
        </article>
      </div>
    </div>
  );
}
