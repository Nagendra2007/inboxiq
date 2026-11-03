import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChatApi, type ChatCitation, type ChatTurn } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { Button } from '../components/ui/Button';
import { Alert } from '../components/ui/Feedback';
import { formatListTime } from '../lib/format';

/**
 * Asking questions about your own mail.
 *
 * Every answer is shown with the emails it was built from, and they are
 * clickable. That is not decoration: the model is reading summaries, not
 * whole emails, so the citation is how you check it rather than take its
 * word — and the one thing worse than no answer is a confident wrong one
 * with nothing to check it against.
 */

const EXAMPLES = [
  'Where are my fee receipts?',
  'What did I get from the bank last month?',
  'Any deliveries arriving this week?',
  'Did anyone ask me for something I have not replied to?',
];

function Citations({ citations }: { citations: ChatCitation[] }) {
  const navigate = useNavigate();
  if (citations.length === 0) return null;

  return (
    <div className="mt-3 space-y-1.5">
      {citations.map((citation) => (
        <button
          key={citation.id}
          type="button"
          onClick={() => navigate(`/inbox?email=${citation.id}`)}
          className="block w-full rounded-lg border border-white/[0.06] bg-white/[0.02] px-3 py-2 text-left transition hover:border-white/15 hover:bg-white/[0.04]"
        >
          <div className="flex items-baseline justify-between gap-3">
            <span className="truncate text-[13px] font-medium text-white/80">
              {citation.subject || '(no subject)'}
            </span>
            {citation.receivedAt && (
              <span className="shrink-0 text-[11px] tabular-nums text-white/35">
                {formatListTime(citation.receivedAt)}
              </span>
            )}
          </div>
          <div className="truncate text-[11px] text-white/40">{citation.sender}</div>
        </button>
      ))}
    </div>
  );
}

export function AskPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>();
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns, busy]);

  const ask = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || busy) return;
      setError('');
      setQuestion('');

      // Shown straight away, with a temporary id. The answer takes a few
      // seconds and a question that vanishes while you wait feels broken.
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
        setConversationId(answer.conversationId);
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
        setTurns((previous) => previous.filter((turn) => turn.id !== pending.id));
        setQuestion(trimmed);
      } finally {
        setBusy(false);
      }
    },
    [busy, conversationId]
  );

  const empty = turns.length === 0;

  return (
    <div className="mx-auto flex h-full w-full max-w-3xl flex-col px-4 py-6">
      <div className="flex-1 space-y-5 overflow-y-auto pb-4">
        {empty && (
          <div className="pt-10">
            <h1 className="text-xl font-semibold text-white/90">Ask about your mail</h1>
            <p className="mt-2 max-w-lg text-sm leading-relaxed text-white/50">
              Questions, not search terms. Every answer comes with the emails it was built from, so you can check it
              rather than take its word.
            </p>
            <div className="mt-5 flex flex-wrap gap-2">
              {EXAMPLES.map((example) => (
                <button
                  key={example}
                  type="button"
                  onClick={() => ask(example)}
                  className="rounded-full border border-white/10 px-3 py-1.5 text-[13px] text-white/60 transition hover:border-white/20 hover:text-white/85"
                >
                  {example}
                </button>
              ))}
            </div>
          </div>
        )}

        {turns.map((turn) =>
          turn.role === 'user' ? (
            <div key={turn.id} className="flex justify-end">
              <div className="max-w-[85%] rounded-2xl rounded-br-sm bg-accent-500/15 px-3.5 py-2 text-sm text-white/90">
                {turn.content}
              </div>
            </div>
          ) : (
            <div key={turn.id} className="max-w-[92%]">
              <div className="whitespace-pre-wrap text-sm leading-relaxed text-white/80">{turn.content}</div>
              <Citations citations={turn.citations} />
            </div>
          )
        )}

        {busy && (
          <div className="flex items-center gap-2 text-sm text-white/40">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white/40" />
            Reading your mail…
          </div>
        )}

        {error && <Alert tone="warning">{error}</Alert>}
        <div ref={endRef} />
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          ask(question);
        }}
        className="flex items-end gap-2 border-t border-white/[0.06] pt-4"
      >
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={(event) => {
            // Enter sends, shift+enter breaks the line: this is a question
            // box, and most questions are one line.
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              ask(question);
            }
          }}
          rows={1}
          placeholder="Ask about your mail…"
          className="min-h-[42px] flex-1 resize-none rounded-xl border border-white/10 bg-white/[0.03] px-3.5 py-2.5 text-sm text-white/85 outline-none placeholder:text-white/30 focus:border-accent-500/60 focus:ring-2 focus:ring-accent-500/20"
        />
        <Button type="submit" loading={busy} disabled={!question.trim()}>
          Ask
        </Button>
      </form>
    </div>
  );
}
