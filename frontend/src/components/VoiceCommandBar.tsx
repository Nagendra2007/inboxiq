import { useEffect, useState } from 'react';
import { ComposeApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { useAudioRecorder } from '../hooks/useAudioRecorder';
import type { ContactDto, VoiceCommandResultDto } from '../types';
import { cn } from '../lib/cn';
import { MicIcon, SparklesIcon, XIcon } from './ui/Icons';
import { VoiceWave } from './ui/VoiceWave';
import { Alert } from './ui/Feedback';

/**
 * Say one instruction — "write to Priya about the contract delay, tell her
 * we need two more days" — and the composer's three fields fill in.
 *
 * It records and transcribes server-side rather than using the browser's own
 * speech recognition. Chrome's version is a call to a Google service that
 * answers with silence when it is unreachable or unhappy, which is a bad
 * thing to build on and impossible to debug from here; Firefox has none at
 * all. The trade is that the words arrive when you stop rather than as you
 * speak, the way a voice note does.
 *
 * The recipient is still never filled in silently. Speech is at its worst on
 * names, and that is the one field where being wrong puts an email in front
 * of a stranger.
 */
export function VoiceCommandBar({
  onFilled,
  disabled,
}: {
  onFilled: (fields: { toAddress?: string; subject?: string; instruction: string }) => void;
  disabled?: boolean;
}) {
  const recorder = useAudioRecorder();
  const [available, setAvailable] = useState<boolean | null>(null);
  const [stage, setStage] = useState<'idle' | 'transcribing' | 'interpreting'>('idle');
  const [heardText, setHeardText] = useState('');
  const [result, setResult] = useState<VoiceCommandResultDto | null>(null);
  const [error, setError] = useState('');

  // Whether this deployment has a transcription key at all. Asked once, and
  // the whole bar stays hidden if not: an offer that cannot work is worse
  // than no offer.
  useEffect(() => {
    let live = true;
    ComposeApi.voiceAvailable()
      .then((r) => live && setAvailable(r.available))
      .catch(() => live && setAvailable(false));
    return () => {
      live = false;
    };
  }, []);

  if (!recorder.supported || available === false) return null;

  async function finish() {
    const clip = await recorder.stop();
    if (!clip || clip.size < 1200) {
      setError("That was too short to make anything of. Hold the button while you talk.");
      return;
    }
    setError('');
    setHeardText('');
    setResult(null);
    setStage('transcribing');
    try {
      const { text } = await ComposeApi.transcribe(clip);
      setHeardText(text);
      setStage('interpreting');
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
      setError(errorMessage(err, 'Could not turn that into text. Try again, or type it instead.'));
    } finally {
      setStage('idle');
    }
  }

  function choose(contact: ContactDto) {
    if (!result?.instruction) return;
    onFilled({ toAddress: contact.email, subject: result.subject ?? undefined, instruction: result.instruction });
    setResult(null);
  }

  const busy = stage !== 'idle';
  const recording = recorder.recording;

  return (
    <div
      className={cn(
        'rounded-xl border p-3 transition-colors',
        recording ? 'border-accent-400/40 bg-accent-500/[0.06]' : 'border-white/[0.07] bg-white/[0.02]'
      )}
    >
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={recording ? finish : recorder.start}
          disabled={disabled || busy || recorder.starting}
          aria-pressed={recording}
          className={cn(
            'relative inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full transition',
            recording
              ? 'bg-rose-500 text-white'
              : 'bg-accent-500 text-on-accent hover:bg-accent-400 disabled:opacity-40'
          )}
        >
          {/* A halo that breathes with the voice rather than on a timer, so
              it stays still when the microphone is picking nothing up. */}
          {recording && (
            <span
              aria-hidden="true"
              className="absolute inset-0 rounded-full bg-rose-500/30 transition-transform duration-100 ease-out motion-reduce:hidden"
              style={{ transform: `scale(${1 + recorder.level * 0.75})` }}
            />
          )}
          <MicIcon className="relative h-4 w-4" />
          <span className="sr-only">{recording ? 'Stop and transcribe' : 'Speak an instruction'}</span>
        </button>

        <div className="min-w-0 flex-1">
          {stage === 'transcribing' ? (
            <span className="flex items-center gap-2 text-sm text-white/60">
              <SparklesIcon className="h-3.5 w-3.5 animate-pulse text-accent-400" />
              Turning that into words…
            </span>
          ) : stage === 'interpreting' ? (
            <span className="flex items-center gap-2 text-sm text-white/60">
              <SparklesIcon className="h-3.5 w-3.5 animate-pulse text-accent-400" />
              Working out what you meant…
            </span>
          ) : recorder.starting ? (
            <span className="text-sm text-white/50">Opening the microphone…</span>
          ) : recording ? (
            <span className="flex items-center gap-2.5 text-sm text-rose-200/80">
              <VoiceWave level={recorder.level} className="text-rose-300" />
              <span className="tabular-nums">
                {String(Math.floor(recorder.seconds / 60)).padStart(2, '0')}:
                {String(recorder.seconds % 60).padStart(2, '0')}
              </span>
              <span className="text-rose-200/50">— press Done when you finish</span>
            </span>
          ) : (
            <span className="block text-sm text-white/45">
              Or say it: <span className="text-white/60">"write to Priya about the contract delay"</span>
            </span>
          )}
        </div>

        {recording && (
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={finish}
              className="rounded-lg bg-white/[0.08] px-3 py-1.5 text-xs font-semibold text-white/85 transition hover:bg-white/[0.14]"
            >
              Done
            </button>
            <button
              type="button"
              onClick={() => {
                recorder.cancel();
                setResult(null);
              }}
              className="text-xs font-medium text-white/40 transition hover:text-white/70"
            >
              Cancel
            </button>
          </div>
        )}
      </div>

      {/* What it heard, kept visible so a wrong word is obvious before the
          draft is generated from it. */}
      {heardText && !recording && (
        <div className="mt-3 rounded-lg bg-black/20 px-3 py-2 text-sm leading-relaxed text-white/80">{heardText}</div>
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

      {(error || recorder.error) && (
        <div className="mt-3">
          <Alert tone="danger">{error || recorder.error}</Alert>
        </div>
      )}
    </div>
  );
}
