import { useEffect, useRef, useState } from 'react';
import { ComposeApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { useSpeechRecognition } from '../hooks/useSpeechRecognition';
import type { ContactDto, VoiceCommandResultDto } from '../types';
import { cn } from '../lib/cn';
import { MicIcon, SparklesIcon, XIcon } from './ui/Icons';
import { VoiceWave } from './ui/VoiceWave';
import { Alert } from './ui/Feedback';

/**
 * Say the whole instruction once — "write to Priya about the contract delay,
 * tell her we need two more days" — and the composer's three fields fill in.
 *
 * What it will not do is pick a recipient quietly. Speech recognition is at
 * its worst on names, which is the one field where being wrong means an
 * email in front of a stranger. So even a single confident match has to be
 * accepted; several are offered as a choice; none says so plainly. After
 * that it is the ordinary draft-review-send path, which already never sends
 * anything unseen.
 */
export function VoiceCommandBar({
  onFilled,
  disabled,
}: {
  onFilled: (fields: { toAddress?: string; subject?: string; instruction: string }) => void;
  disabled?: boolean;
}) {
  const speech = useSpeechRecognition();
  const [thinking, setThinking] = useState(false);
  const [result, setResult] = useState<VoiceCommandResultDto | null>(null);
  const [error, setError] = useState('');
  const transcriptRef = useRef<HTMLDivElement>(null);

  // Keep the newest words in view as they arrive, the way a dictation box
  // on a phone does.
  useEffect(() => {
    const box = transcriptRef.current;
    if (box) box.scrollTop = box.scrollHeight;
  }, [speech.transcript, speech.interim]);

  // Nothing to offer where the browser has no recognition at all. Firefox
  // users get the normal form, which has always worked.
  if (!speech.supported) return null;

  async function interpret() {
    speech.stop();
    const text = speech.transcript.trim();
    if (!text) {
      setError("Didn't catch anything. Try again, a little closer to the mic.");
      return;
    }
    setThinking(true);
    setError('');
    try {
      const parsed = await ComposeApi.voiceCommand(text);
      setResult(parsed);
      if (!parsed.instruction) {
        setError("Couldn't make an instruction out of that. Try saying who it's for and what it should say.");
        return;
      }
      if (parsed.candidates.length === 0) {
        onFilled({ subject: parsed.subject ?? undefined, instruction: parsed.instruction });
      }
    } catch (err) {
      setError(errorMessage(err, 'Could not interpret that. Try again, or type it instead.'));
    } finally {
      setThinking(false);
    }
  }

  function choose(contact: ContactDto) {
    if (!result?.instruction) return;
    onFilled({
      toAddress: contact.email,
      subject: result.subject ?? undefined,
      instruction: result.instruction,
    });
    setResult(null);
  }

  const listening = speech.listening;
  const hasWords = Boolean(speech.transcript || speech.interim);

  return (
    <div
      className={cn(
        'rounded-xl border p-3 transition-colors',
        listening ? 'border-accent-400/40 bg-accent-500/[0.06]' : 'border-white/[0.07] bg-white/[0.02]'
      )}
    >
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={listening ? interpret : speech.start}
          disabled={disabled || thinking}
          aria-pressed={listening}
          className={cn(
            'relative inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full transition',
            listening
              ? 'bg-rose-500 text-white'
              : 'bg-accent-500 text-on-accent hover:bg-accent-400 disabled:opacity-40'
          )}
        >
          {/* A halo that breathes with the voice rather than on a timer, so
              it stays still when the mic is not picking anything up. */}
          {listening && (
            <span
              aria-hidden="true"
              className="absolute inset-0 rounded-full bg-rose-500/30 transition-transform duration-100 ease-out motion-reduce:hidden"
              style={{ transform: `scale(${1 + speech.level * 0.75})` }}
            />
          )}
          <MicIcon className="relative h-4 w-4" />
          <span className="sr-only">{listening ? 'Stop and interpret' : 'Speak an instruction'}</span>
        </button>

        <div className="min-w-0 flex-1">
          {thinking ? (
            <span className="flex items-center gap-2 text-sm text-white/60">
              <SparklesIcon className="h-3.5 w-3.5 animate-pulse text-accent-400" />
              Working out what you meant…
            </span>
          ) : listening ? (
            <span className="flex items-center gap-2.5 text-sm text-rose-200/80">
              <VoiceWave level={speech.level} className="text-rose-300" />
              {hasWords ? 'Listening…' : 'Go ahead — say who it’s for and what it should say.'}
            </span>
          ) : (
            <span className="block text-sm text-white/45">
              Or say it: <span className="text-white/60">"write to Priya about the contract delay"</span>
            </span>
          )}
        </div>

        {listening && (
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={interpret}
              className="rounded-lg bg-white/[0.08] px-3 py-1.5 text-xs font-semibold text-white/85 transition hover:bg-white/[0.14]"
            >
              Done
            </button>
            <button
              type="button"
              onClick={() => {
                speech.stop();
                setResult(null);
              }}
              className="text-xs font-medium text-white/40 transition hover:text-white/70"
            >
              Cancel
            </button>
          </div>
        )}
      </div>

      {/* The live transcript. Settled words in full strength, the current
          guess faint behind them, because the guess will change and watching
          it rewrite itself is how you know it is still listening. */}
      {listening && hasWords && (
        <div
          ref={transcriptRef}
          className="mt-3 max-h-24 overflow-y-auto scrollbar-thin rounded-lg bg-black/20 px-3 py-2 text-sm leading-relaxed"
          aria-live="polite"
        >
          <span className="text-white/85">{speech.transcript}</span>
          {speech.interim && <span className="text-white/35"> {speech.interim}</span>}
        </div>
      )}

      {/* The confirmation. A name out of a transcript never fills the To
          field on its own, however sure the match looks. */}
      {result?.instruction && result.candidates.length > 0 && (
        <div className="mt-3 border-t border-white/[0.07] pt-3">
          <p className="mb-2 text-xs text-white/50">
            {result.candidates.length === 1 ? (
              <>
                You said <span className="text-white/75">"{result.heardRecipient}"</span>. Send to:
              </>
            ) : (
              <>
                You said <span className="text-white/75">"{result.heardRecipient}"</span> — which one?
              </>
            )}
          </p>
          <div className="flex flex-wrap gap-2">
            {result.candidates.map((contact) => (
              <button
                key={contact.email}
                type="button"
                onClick={() => choose(contact)}
                className="rounded-lg border border-white/10 bg-white/[0.04] px-3 py-1.5 text-left text-xs transition hover:border-accent-400/50 hover:bg-accent-500/10"
              >
                <span className="block font-medium text-white/85">{contact.name || contact.email}</span>
                {contact.name && <span className="block text-white/45">{contact.email}</span>}
              </button>
            ))}
            <button
              type="button"
              onClick={() => {
                onFilled({ subject: result.subject ?? undefined, instruction: result.instruction! });
                setResult(null);
              }}
              className="rounded-lg px-3 py-1.5 text-xs text-white/45 transition hover:text-white/70"
            >
              <XIcon className="mr-1 inline h-3 w-3" />
              None of these
            </button>
          </div>
        </div>
      )}

      {result?.instruction && result.candidates.length === 0 && result.heardRecipient && (
        <p className="mt-2 text-xs text-amber-300/80">
          Nobody called "{result.heardRecipient}" has written to you, so the recipient is still blank — type it in.
        </p>
      )}

      {result && !result.confident && result.instruction && (
        <p className="mt-2 text-xs text-white/40">That was hard to make out — check the wording before you generate.</p>
      )}

      {(error || speech.error) && (
        <div className="mt-3">
          <Alert tone="danger">{error || speech.error}</Alert>
        </div>
      )}
    </div>
  );
}
