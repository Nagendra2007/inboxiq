import { useState } from 'react';
import { ComposeApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { AdjustButton, GeneratedReplyDto } from '../types';
import { cn } from '../lib/cn';
import { isValidEmail } from '../lib/format';
import { Dialog, ConfirmDialog } from './ui/Dialog';
import { Button } from './ui/Button';
import { Alert } from './ui/Feedback';
import { useToast } from './ui/Toast';
import { ArrowRightIcon, CheckCircleIcon, ChevronLeftIcon, SendIcon, SparklesIcon } from './ui/Icons';
import { DraftEditor, SendPreview } from './DraftEditor';

type Step = 'write' | 'edit' | 'confirm' | 'sent';

const STEPS: { key: Exclude<Step, 'sent'>; label: string }[] = [
  { key: 'write', label: 'Describe' },
  { key: 'edit', label: 'Edit' },
  { key: 'confirm', label: 'Send' },
];

function Stepper({ step }: { step: Step }) {
  const activeIndex = step === 'sent' ? STEPS.length : STEPS.findIndex((s) => s.key === step);
  return (
    <ol className="mb-5 flex items-center gap-2 text-xs font-medium" aria-label="Progress">
      {STEPS.map((s, i) => (
        <li key={s.key} className="flex items-center gap-2">
          <span
            className={cn(
              'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold',
              i < activeIndex && 'bg-accent-500/15 text-accent-300',
              i === activeIndex && 'bg-accent-500 text-ink-900',
              i > activeIndex && 'bg-white/[0.06] text-white/35'
            )}
            aria-current={i === activeIndex ? 'step' : undefined}
          >
            {i + 1}
          </span>
          <span className={i === activeIndex ? 'text-white/85' : 'text-white/35'}>{s.label}</span>
          {i < STEPS.length - 1 && <span className="h-px w-6 bg-white/10" aria-hidden="true" />}
        </li>
      ))}
    </ol>
  );
}

/**
 * Compose a brand-new email through the same AI instruction -> draft ->
 * adjust -> explicit review -> confirm & send workflow as ReplyComposer.
 * Nothing is ever sent without the user clicking "Confirm & send" on text
 * they've seen and can edit.
 */
export function ComposeModal({ onClose, onSent }: { onClose: () => void; onSent?: () => void }) {
  const toast = useToast();
  const [step, setStep] = useState<Step>('write');
  const [toAddress, setToAddress] = useState('');
  const [subject, setSubject] = useState('');
  const [instruction, setInstruction] = useState('');
  const [draft, setDraft] = useState<GeneratedReplyDto | null>(null);
  const [editedText, setEditedText] = useState('');
  const [generating, setGenerating] = useState(false);
  const [adjusting, setAdjusting] = useState<AdjustButton | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toTouched, setToTouched] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);

  const toInvalid = toTouched && toAddress.trim() !== '' && !isValidEmail(toAddress);
  const canGenerate = isValidEmail(toAddress) && subject.trim() !== '' && instruction.trim() !== '';
  const busy = generating || adjusting !== null || sending;
  const hasWork = step !== 'sent' && (instruction.trim() !== '' || draft !== null);

  const requestClose = () => {
    if (busy) return;
    if (hasWork) setConfirmDiscard(true);
    else onClose();
  };

  const generate = async () => {
    setToTouched(true);
    if (!canGenerate) {
      setError('Add a valid recipient, a subject, and what you’d like the email to say.');
      return;
    }
    setGenerating(true);
    setError(null);
    try {
      const result = await ComposeApi.generate(toAddress.trim(), subject.trim(), instruction.trim());
      setDraft(result);
      setEditedText(result.content);
      setStep('edit');
    } catch (err) {
      setError(errorMessage(err, 'Could not generate a draft. Please try again.'));
    } finally {
      setGenerating(false);
    }
  };

  const adjust = async (button: AdjustButton) => {
    if (!draft) return;
    setAdjusting(button);
    setError(null);
    try {
      const result = await ComposeApi.adjust(draft.id, { button });
      setDraft(result);
      setEditedText(result.content);
    } catch (err) {
      setError(errorMessage(err, 'Could not adjust the draft. Please try again.'));
    } finally {
      setAdjusting(null);
    }
  };

  const confirmSend = async () => {
    if (!draft) return;
    setSending(true);
    setError(null);
    try {
      await ComposeApi.send(draft.id, editedText, toAddress.trim(), subject.trim());
      setStep('sent');
      toast.success('Email sent', `Delivered from your Gmail to ${toAddress.trim()}.`);
      onSent?.();
    } catch (err) {
      setError(errorMessage(err, 'Could not send the email. Please try again.'));
    } finally {
      setSending(false);
    }
  };

  const reset = () => {
    setStep('write');
    setToAddress('');
    setSubject('');
    setInstruction('');
    setDraft(null);
    setEditedText('');
    setError(null);
    setToTouched(false);
  };

  const footer =
    step === 'write' ? (
      <>
        <p className="mr-auto hidden text-2xs text-white/30 sm:block">
          <kbd className="kbd">Ctrl</kbd> + <kbd className="kbd">Enter</kbd> to generate
        </p>
        <Button variant="ghost" onClick={requestClose}>
          Cancel
        </Button>
        <Button
          variant="primary"
          onClick={generate}
          loading={generating}
          disabled={!canGenerate}
          icon={<SparklesIcon className="h-4 w-4" />}
        >
          {generating ? 'Drafting…' : 'Generate draft'}
        </Button>
      </>
    ) : step === 'edit' ? (
      <>
        <Button
          variant="ghost"
          className="sm:mr-auto"
          disabled={busy}
          onClick={() => {
            setDraft(null);
            setEditedText('');
            setStep('write');
          }}
          icon={<ChevronLeftIcon className="h-4 w-4" />}
        >
          Start over
        </Button>
        <Button
          variant="primary"
          disabled={busy || !editedText.trim()}
          onClick={() => {
            setError(null);
            setStep('confirm');
          }}
        >
          Review &amp; send
          <ArrowRightIcon className="h-4 w-4" />
        </Button>
      </>
    ) : step === 'confirm' ? (
      <>
        <Button
          variant="ghost"
          className="sm:mr-auto"
          disabled={sending}
          onClick={() => setStep('edit')}
          icon={<ChevronLeftIcon className="h-4 w-4" />}
        >
          Back to edit
        </Button>
        <Button variant="success" onClick={confirmSend} loading={sending} icon={<SendIcon className="h-4 w-4" />}>
          {sending ? 'Sending…' : 'Confirm & send'}
        </Button>
      </>
    ) : (
      <>
        <Button variant="ghost" onClick={reset}>
          Write another
        </Button>
        <Button variant="primary" onClick={onClose} data-autofocus>
          Done
        </Button>
      </>
    );

  return (
    <>
      <Dialog
        open
        onClose={requestClose}
        dismissible={!busy}
        title="New message"
        description={step === 'sent' ? undefined : 'Describe what you want to say — InboxIQ drafts it, you approve it.'}
        size="lg"
        footer={footer}
      >
        {step !== 'sent' && <Stepper step={step} />}

        {step === 'sent' ? (
          <div className="flex flex-col items-center py-8 text-center animate-scale-in">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-300">
              <CheckCircleIcon className="h-6 w-6" />
            </span>
            <p className="text-base font-semibold text-white">Email sent</p>
            <p className="mt-1 text-sm text-white/50">
              “{subject}” is on its way to {toAddress}.
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="field-label">To</span>
                <input
                  value={toAddress}
                  onChange={(event) => setToAddress(event.target.value)}
                  onBlur={() => setToTouched(true)}
                  disabled={step !== 'write'}
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  placeholder="name@example.com"
                  aria-invalid={toInvalid || undefined}
                  data-autofocus
                  className={cn('field', toInvalid && 'border-rose-500/60 focus:border-rose-500/60 focus:ring-rose-500/20')}
                />
                {toInvalid && <span className="mt-1 block text-xs text-rose-300">Enter a valid email address.</span>}
              </label>
              <label className="block">
                <span className="field-label">Subject</span>
                <input
                  value={subject}
                  onChange={(event) => setSubject(event.target.value)}
                  disabled={step !== 'write'}
                  placeholder="What’s this about?"
                  className="field"
                />
              </label>
            </div>

            {step === 'write' && (
              <label className="block">
                <span className="field-label">What should it say?</span>
                <textarea
                  value={instruction}
                  onChange={(event) => setInstruction(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                      event.preventDefault();
                      generate();
                    }
                  }}
                  rows={4}
                  placeholder="e.g. Ask if we can push our 2pm meeting to Thursday, and apologise for the short notice"
                  className="field resize-none"
                />
              </label>
            )}

            {step === 'edit' && (
              <DraftEditor value={editedText} onChange={setEditedText} onAdjust={adjust} adjusting={adjusting} />
            )}

            {step === 'confirm' && (
              <div>
                <span className="field-label">Message</span>
                <SendPreview body={editedText} />
                <p className="mt-2 text-xs text-white/40">Sent from your Gmail account. Nothing else is attached or changed.</p>
              </div>
            )}

            {error && <Alert tone="danger">{error}</Alert>}
          </div>
        )}
      </Dialog>

      <ConfirmDialog
        open={confirmDiscard}
        onClose={() => setConfirmDiscard(false)}
        onConfirm={onClose}
        title="Discard this draft?"
        description="Your instruction and the AI draft will be lost."
        confirmLabel="Discard"
        cancelLabel="Keep editing"
      />
    </>
  );
}
