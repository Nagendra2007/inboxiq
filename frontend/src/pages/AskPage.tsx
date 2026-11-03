import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChatApi, type ChatCitation, type ChatTurn } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { Alert } from '../components/ui/Feedback';
import { AskIcon } from '../components/ui/Icons';
import { formatListTime, parseSender } from '../lib/format';

/**
 * Asking questions about your own mail.
 *
 * Built phone-first, because that is where it gets used: one column, the
 * question box pinned above the tab bar, and the answer given the full
 * width rather than squeezed into a chat bubble. A bubble suits a short
 * message; an answer with four emails under it is a block of reading.
 *
 * Every answer carries the emails it came from, and they are tappable.
 * That is not decoration — the model reads summaries, not whole emails,
 * so the citation is how you check it rather than take its word.
 */

const EXAMPLES = [
  'Where are my fee receipts?',
  'What did the bank send last month?',
  'Any deliveries coming this week?',
  'Who is waiting on a reply from me?',
];

function Citations({ citations }: { citations: ChatCitation[] }) {
  const navigate = useNavigate();
  if (citations.length === 0) return null;

  return (
    <div className="mt-3 space-y-1.5">
      <p className="text-[11px] font-medium uppercase tracking-wide text-white/25">
        {citations.length === 1 ? 'From this email' : `From these ${citations.length} emails`}
      </p>
      {citations.map((citation) => {
        const sender = parseSender(citation.sender);
        return (
          <button
            key={citation.id}
            type="button"
            onClick={() => navigate(`/inbox?email=${citation.id}`)}
            className="flex w-full items-start gap-2.5 rounded-xl border border-white/[0.07] bg-white/[0.02] p-2.5 text-left transition hover:border-white/15 hover:bg-white/[0.045] active:scale-[0.99]"
          >
            <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-accent-500/15 text-[11px] font-semibold uppercase text-accent-300">
              {(sender.name || sender.email || '?').slice(0, 1)}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-baseline justify-between gap-2">
                <span className="truncate text-[13px] font-medium leading-snug text-white/85">
                  {citation.subject || '(no subject)'}
                </span>
                {citation.receivedAt && (
                  <span className="shrink-0 text-[11px] tabular-nums text-white/30">
                    {formatListTime(citation.receivedAt)}
                  </span>
                )}
              </span>
              <span className="mt-0.5 block truncate text-[11.5px] text-white/40">
                {sender.name || sender.email}
              </span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

interface Conversation {
  id: string;
  title: string;
  updatedAt: string;
}

export function AskPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const endRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns, busy]);

  // Grows with the question rather than scrolling inside two lines, and
  // shrinks back once it is sent.
  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    box.style.height = 'auto';
    box.style.height = `${Math.min(box.scrollHeight, 140)}px`;
  }, [question]);

  const refreshConversations = useCallback(() => {
    ChatApi.conversations()
      .then(setConversations)
      .catch(() => {});
  }, []);

  useEffect(refreshConversations, [refreshConversations]);

  const open = useCallback(async (id: string) => {
    setShowHistory(false);
    setError('');
    try {
      const history = await ChatApi.history(id);
      setTurns(history);
      setConversationId(id);
    } catch (err) {
      setError(errorMessage(err, 'That conversation could not be opened.'));
    }
  }, []);

  const startNew = useCallback(() => {
    setTurns([]);
    setConversationId(undefined);
    setShowHistory(false);
    setError('');
  }, []);

  const ask = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || busy) return;
      setError('');
      setQuestion('');

      const pending: ChatTurn = {
        id: `pending-${Date.now()}`,
        role: 'user',
        content: trimmed,
        at: new Date().toISOString(),
        citations: [],
      };
      setTurns((previous) => [...previous, pending]);
      setBusy(true);

      try {
        const answer = await ChatApi.ask(trimmed, conversationId);
        const isNew = !conversationId;
        setConversationId(answer.conversationId);
        if (isNew) refreshConversations();
        setTurns((previous) => [
          ...previous,
          {
            id: `answer-${Date.now()}`,
            role: 'assistant',
            content: answer.answer,
            at: new Date().toISOString(),
            citations: answer.citations,
          },
        ]);
      } catch (err) {
        setError(errorMessage(err, 'That question could not be answered just now.'));
        // The question goes back in the box rather than being lost, so a
        // failure costs a tap instead of retyping it.
        setTurns((previous) => previous.filter((turn) => turn.id !== pending.id));
        setQuestion(trimmed);
      } finally {
        setBusy(false);
      }
    },
    [busy, conversationId, refreshConversations]
  );

  const empty = turns.length === 0;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {!empty && (
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-white/[0.06] px-4 py-2.5">
          <button
            type="button"
            onClick={() => setShowHistory((isOpen) => !isOpen)}
            className="truncate text-[13px] text-white/45 transition hover:text-white/75"
          >
            {showHistory ? 'Hide earlier' : `Earlier questions (${conversations.length})`}
          </button>
          <button
            type="button"
            onClick={startNew}
            className="shrink-0 rounded-lg border border-white/10 px-2.5 py-1 text-[12.5px] text-white/60 transition hover:border-white/20 hover:text-white/85 active:scale-95"
          >
            New
          </button>
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto w-full max-w-2xl px-4 py-5">
          {showHistory && (
            <div className="mb-5 space-y-0.5 rounded-xl border border-white/[0.06] p-1.5">
              {conversations.length === 0 && (
                <p className="px-2 py-1.5 text-[13px] text-white/35">Nothing yet.</p>
              )}
              {conversations.map((conversation) => (
                <button
                  key={conversation.id}
                  type="button"
                  onClick={() => open(conversation.id)}
                  className="flex w-full items-baseline justify-between gap-3 rounded-lg px-2.5 py-2 text-left transition hover:bg-white/[0.05]"
                >
                  <span className="truncate text-[13px] text-white/70">{conversation.title}</span>
                  <span className="shrink-0 text-[11px] text-white/30">
                    {formatListTime(conversation.updatedAt)}
                  </span>
                </button>
              ))}
            </div>
          )}

          {empty && (
            <div className="pt-6 sm:pt-12">
              <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-accent-500/15">
                <AskIcon className="h-5 w-5 text-accent-300" />
              </span>
              <h1 className="mt-4 text-[22px] font-semibold leading-tight text-white/90">Ask about your mail</h1>
              <p className="mt-2 text-[14px] leading-relaxed text-white/50">
                Questions, not search terms. Every answer comes with the emails it was built from, so you can check it
                rather than take its word.
              </p>
              <div className="mt-5 flex flex-col gap-2">
                {EXAMPLES.map((example) => (
                  <button
                    key={example}
                    type="button"
                    onClick={() => ask(example)}
                    className="rounded-xl border border-white/[0.08] bg-white/[0.02] px-3.5 py-2.5 text-left text-[13.5px] text-white/65 transition hover:border-white/20 hover:text-white/90 active:scale-[0.99]"
                  >
                    {example}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="space-y-6">
            {turns.map((turn) =>
              turn.role === 'user' ? (
                <div key={turn.id} className="flex justify-end">
                  <p className="max-w-[88%] rounded-2xl rounded-br-md bg-accent-500/15 px-3.5 py-2 text-[14px] leading-relaxed text-white/90">
                    {turn.content}
                  </p>
                </div>
              ) : (
                <div key={turn.id}>
                  <p className="whitespace-pre-wrap text-[15px] leading-[1.65] text-white/85">{turn.content}</p>
                  <Citations citations={turn.citations} />
                </div>
              )
            )}

            {busy && (
              <div className="flex items-center gap-2 text-[13.5px] text-white/40">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent-400" />
                Reading your mail…
              </div>
            )}

            {error && <Alert tone="warning">{error}</Alert>}
          </div>
          <div ref={endRef} className="h-1" />
        </div>
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          ask(question);
        }}
        className="shrink-0 border-t border-white/[0.06] bg-ink-900/95 px-4 pb-3 pt-3 backdrop-blur"
      >
        <div className="mx-auto flex w-full max-w-2xl items-end gap-2">
          <textarea
            ref={boxRef}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              // Only with a real keyboard. On a phone, Enter makes a new
              // line — the send button is right there, and nothing is more
              // annoying than a half-typed question sending itself.
              if (event.key === 'Enter' && !event.shiftKey && window.matchMedia('(min-width: 768px)').matches) {
                event.preventDefault();
                ask(question);
              }
            }}
            rows={1}
            placeholder="Ask about your mail…"
            className="max-h-[140px] min-h-[44px] flex-1 resize-none rounded-2xl border border-white/10 bg-white/[0.04] px-4 py-2.5 text-[15px] leading-snug text-white/90 outline-none placeholder:text-white/30 focus:border-accent-500/60 focus:ring-2 focus:ring-accent-500/20"
          />
          <button
            type="submit"
            disabled={!question.trim() || busy}
            aria-label="Ask"
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-accent-500 text-on-accent transition active:scale-95 disabled:bg-white/[0.06] disabled:text-white/25"
          >
            {busy ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
            ) : (
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={2}>
                <path d="M5 12h13M13 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
