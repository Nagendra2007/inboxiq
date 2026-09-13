import { useState } from 'react';
import { EmailApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { AdjustButton, GeneratedReplyDto } from '../types';
import { isValidEmail, parseSender } from '../lib/format';
import { Button } from './ui/Button';
import { Alert } from './ui/Feedback';
import { useToast } from './ui/Toast';
import { ArrowRightIcon, CheckCircleIcon, ChevronLeftIcon, SendIcon, SparklesIcon } from './ui/Icons';
import { DraftEditor, SendPreview } from './DraftEditor';

const SUGGESTIONS = ['Confirm and thank them', 'Politely decline', 'Ask for more details', 'Say I’ll get back to them tomorrow'];

type Stage = 'prompt' | 'edit' | 'confirm' | 'sent';

/**
 * The AI reply workflow: one-line instruction -> draft -> review/edit/adjust
 * -> explicit confirm -> send. Nothing here ever calls sendReply without the
 * user first clicking "Confirm & send", and the text sent is always exactly
 * what's in the editable textarea at that moment — including any manual edits.
 */
export function ReplyComposer({
  emailId,
  sender,
  subject,
}: {
  emailId: string;
  sender: string | null;
  subject: string | null;
}) {
  const toast = useToast();
  const [stage, setStage] = useState<Stage>('prompt');
  const [instruction, setInstruction] = useState('');
  const [draft, setDraft] = useState<GeneratedReplyDto | null>(null);
  const [editedText, setEditedText] = useState('');
  const [generating, setGenerating] = useState(false);
  const [adjusting, setAdjusting] = useState<AdjustButton | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toAddress, setToAddress] = useState('');
  const [replySubject, setReplySubject] = useState('');

  const recipientName = parseSender(sender).name;

  const generate = async () => {
    if (!instruction.trim()) {
      setError('Tell InboxIQ what you’d like the reply to say.');
      return;
    }
    setGenerating(true);
    setError(null);
    try {
      const result = await EmailApi.generateReply(emailId, instruction.trim());
      setDraft(result);
      setEditedText(result.content);
      setToAddress(parseSender(sender).email || sender || '');
      setReplySubject(subject?.toLowerCase().startsWith('re:') ? subject : `Re: ${subject || ''}`.trim());
      setStage('edit');
    } catch (err) {
      setError(errorMessage(err, 'Could not generate a reply. Please try again.'));
    } finally {
      setGenerating(false);
    }
  };

  const adjust = async (button: AdjustButton) => {
    if (!draft) return;
    setAdjusting(button);
    setError(null);
    try {
      const result = await EmailApi.adjustReply(emailId, draft.id, { button });
      setDraft(result);
      setEditedText(result.content);
    } catch (err) {
      setError(errorMessage(err, 'Could not adjust the reply. Please try again.'));
    } finally {
      setAdjusting(null);
    }
  };

  const confirmSend = async () => {
    if (!draft) return;
    if (!isValidEmail(toAddress)) {
      setError('Enter a valid recipient email address.');
      return;
    }
    setSending(true);
    setError(null);
    try {
      await EmailApi.sendReply(emailId, draft.id, editedText, toAddress.trim(), replySubject.trim());
      setStage('sent');
      toast.success('Reply sent', `Delivered from your Gmail to ${toAddress.trim()}.`);
    } catch (err) {
      setError(errorMessage(err, 'Could not send the reply. Please try again.'));
    } finally {
      setSending(false);
    }
  };

  const discard = () => {
    setDraft(null);
    setEditedText('');
    setInstruction('');
    setError(null);
    setStage('prompt');
  };

  if (stage === 'sent') {
    return (
      <div className="card flex items-center gap-3 px-5 py-4 animate-fade-in">
        <span className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-300">
          <CheckCircleIcon className="h-5 w-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-white/90">Reply sent</p>
          <p className="truncate text-xs text-white/45">Sent via Gmail to {toAddress}</p>
        </div>
        <Button variant="ghost" size="sm" onClick={discard}>
          Write another
        </Button>
      </div>
    );
  }

  return (
    <section aria-label="Reply with AI" className="card overflow-hidden">
      <header className="flex items-center gap-2.5 border-b border-white/[0.06] px-5 py-3.5">
        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-accent-500/10 text-accent-400">
          <SparklesIcon className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-white/90">
            {stage === 'confirm' ? 'Review before sending' : 'Reply with AI'}
          </h3>
          <p className="truncate text-xs text-white/40">
            {stage === 'prompt' && `Describe your reply to ${recipientName} in a few words.`}
            {stage === 'edit' && 'Edit freely or adjust the tone — nothing is sent until you confirm.'}
            {stage === 'confirm' && 'This is exactly what will be sent from your Gmail.'}
          </p>
        </div>
      </header>

      <div className="p-5">
        {stage === 'prompt' && (
          <div className="space-y-3">
            <textarea
              value={instruction}
              onChange={(event) => setInstruction(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                  event.preventDefault();
                  generate();
                }
              }}
              rows={2}
              placeholder="e.g. Say yes, I can meet Thursday at 3pm"
              aria-label="What should the reply say?"
              className="field resize-none"
            />
            <div className="flex flex-wrap items-center gap-1.5">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => setInstruction(suggestion)}
                  className="h-7 rounded-full border border-white/[0.08] px-2.5 text-xs text-white/50 transition hover:border-white/20 hover:text-white/85"
                >
                  {suggestion}
                </button>
              ))}
            </div>
            <div className="flex items-center justify-between gap-3 pt-1">
              <p className="hidden text-2xs text-white/30 sm:block">
                <kbd className="kbd">Ctrl</kbd> + <kbd className="kbd">Enter</kbd> to generate
              </p>
              <Button
                variant="primary"
                onClick={generate}
                loading={generating}
                disabled={!instruction.trim()}
                icon={<SparklesIcon className="h-4 w-4" />}
                className="ml-auto"
              >
                {generating ? 'Drafting…' : 'Generate draft'}
              </Button>
            </div>
          </div>
        )}

        {stage === 'edit' && (
          <div className="space-y-4">
            <DraftEditor value={editedText} onChange={setEditedText} onAdjust={adjust} adjusting={adjusting} />
            <div className="flex items-center justify-end gap-2">
              <Button variant="ghost" onClick={discard} disabled={adjusting !== null}>
                Discard
              </Button>
              <Button
                variant="primary"
                onClick={() => {
                  setError(null);
                  setStage('confirm');
                }}
                disabled={adjusting !== null || !editedText.trim()}
              >
                Review &amp; send
                <ArrowRightIcon className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}

        {stage === 'confirm' && (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="field-label">To</span>
                <input
                  value={toAddress}
                  onChange={(event) => setToAddress(event.target.value)}
                  type="email"
                  className="field"
                />
              </label>
              <label className="block">
                <span className="field-label">Subject</span>
                <input value={replySubject} onChange={(event) => setReplySubject(event.target.value)} className="field" />
              </label>
            </div>
            <SendPreview body={editedText} />
            <div className="flex items-center justify-between gap-2 pt-1">
              <Button variant="ghost" onClick={() => setStage('edit')} disabled={sending} icon={<ChevronLeftIcon className="h-4 w-4" />}>
                Back to edit
              </Button>
              <Button variant="success" onClick={confirmSend} loading={sending} icon={<SendIcon className="h-4 w-4" />}>
                {sending ? 'Sending…' : 'Confirm & send'}
              </Button>
            </div>
          </div>
        )}

        {error && (
          <Alert tone="danger" className="mt-4">
            {error}
          </Alert>
        )}
      </div>
    </section>
  );
}
