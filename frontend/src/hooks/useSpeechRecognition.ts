import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Speech recognition, where the browser has it.
 *
 * Chrome and Edge expose it as `webkitSpeechRecognition`; Firefox has none
 * and Safari's is partial, so `supported` is the first thing every caller
 * checks — a mic button that does nothing is worse than no mic button.
 *
 * Note for anyone wondering where the audio goes: in Chrome this is not
 * on-device. The browser streams it to Google for transcription, the same
 * way a Google Assistant query does. That is fine for dictating an email
 * about to go out through Gmail anyway, and it is why this never starts on
 * its own.
 */

// Not in lib.dom for every TS version, and the vendor-prefixed name never is.
type SpeechRecognitionInstance = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onstart: (() => void) | null;
  onresult: ((event: SpeechRecognitionResultEvent) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
};

interface SpeechRecognitionResultEvent {
  resultIndex: number;
  results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>;
}

type SpeechRecognitionConstructor = new () => SpeechRecognitionInstance;

function constructor(): SpeechRecognitionConstructor | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  return w.SpeechRecognition || w.webkitSpeechRecognition || null;
}

/** Errors worth stopping for, as opposed to ones worth restarting through. */
const FATAL = new Set(['not-allowed', 'service-not-allowed', 'audio-capture']);

function describe(code: string): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone access was blocked. Allow it in your browser, then try again.';
    case 'no-speech':
      return '';
    case 'audio-capture':
      return 'No microphone was found.';
    case 'network':
      return 'Speech recognition needs a connection, and it could not reach the service.';
    case 'aborted':
      return '';
    default:
      return 'Speech recognition stopped unexpectedly. Try again.';
  }
}

export interface SpeechRecognitionState {
  /** False in Firefox and older Safari. Check before rendering a mic. */
  supported: boolean;
  listening: boolean;
  /** Clicked, but the engine has not confirmed yet. */
  starting: boolean;
  /** Settled words. Grows as you speak; cleared by `start`. */
  transcript: string;
  /** The current guess, still being revised. Show it faint; never store it. */
  interim: string;
  /** Loudness right now, 0 to 1, for a meter that proves it can hear you. */
  level: number;
  error: string;
  start: () => void;
  stop: () => void;
}

export function useSpeechRecognition(lang = 'en-IN'): SpeechRecognitionState {
  const [supported] = useState(() => constructor() !== null);
  const [listening, setListening] = useState(false);
  // True between the click and the engine confirming, so the button can say
  // so instead of looking inert while Chrome asks for permission.
  const [starting, setStarting] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [interim, setInterim] = useState('');
  const [level, setLevel] = useState(0);
  const [error, setError] = useState('');

  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  // What the user wants, as opposed to what the engine is currently doing.
  // The two drift apart constantly, which is the whole problem below.
  const wantListening = useRef(false);
  // Topped up by each result, drained by the meter. How much the bars move.
  const energyRef = useRef(0);

  useEffect(() => {
    const Ctor = constructor();
    if (!Ctor) return;
    const recognition = new Ctor();
    recognition.lang = lang;
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event) => {
      let settled = '';
      let pending = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const text = result[0]?.transcript ?? '';
        if (result.isFinal) settled += text;
        else pending += text;
      }
      if (settled) setTranscript((prev) => (prev ? `${prev} ${settled.trim()}` : settled.trim()));
      setInterim(pending);
      // Longer bursts push the bars higher, so the meter tracks speaking
      // rather than simply switching on.
      const spoken = (settled + pending).trim().length;
      if (spoken > 0) energyRef.current = Math.min(1, 0.45 + Math.min(0.55, spoken / 40));
    };

    // The engine's own word for it. `listening` follows this rather than
    // the click, so the UI can never show a live microphone over a
    // recogniser that failed to start.
    recognition.onstart = () => {
      setListening(true);
      setStarting(false);
    };

    recognition.onerror = (event) => {
      const message = describe(event.error);
      if (message) setError(message);
      if (FATAL.has(event.error)) {
        wantListening.current = false;
        setStarting(false);
        setListening(false);
      }
    };

    // Chrome ends the session after a few seconds of silence even with
    // continuous = true, so a natural pause mid-sentence otherwise ends
    // dictation and loses the rest. While the user still means to be
    // talking, start it again. This is the single biggest difference
    // between "it keeps cutting me off" and usable dictation.
    recognition.onend = () => {
      setInterim('');
      if (!wantListening.current) {
        setStarting(false);
        setListening(false);
        return;
      }
      try {
        recognition.start();
      } catch {
        wantListening.current = false;
        setStarting(false);
        setListening(false);
      }
    };

    recognitionRef.current = recognition;
    return () => {
      wantListening.current = false;
      recognition.onstart = null;
      recognition.onresult = null;
      recognition.onerror = null;
      recognition.onend = null;
      recognition.abort();
      recognitionRef.current = null;
    };
  }, [lang]);

  // The meter runs off the recogniser's own output, not off a second
  // microphone stream.
  //
  // It used to open its own getUserMedia stream and read an AnalyserNode,
  // which is the obvious way to build a level meter and was a mistake here:
  // the recogniser holds the microphone, and a second reader of the same
  // device can stop it receiving audio at all — on Windows especially. The
  // animation then kept moving while recognition quietly produced nothing,
  // which is the worst possible failure, because the moving bars say it is
  // working.
  //
  // Driving it from the arrival of results instead means a moving bar
  // proves the thing that actually matters: words are being recognised. If
  // it sits flat while you talk, recognition is not hearing you, and that
  // is worth seeing rather than hiding behind a decorative pulse.
  useEffect(() => {
    if (!listening) {
      setLevel(0);
      return;
    }
    const id = window.setInterval(() => {
      setLevel((current) => {
        const target = energyRef.current;
        // Decay what the last result deposited, so speech makes the bars
        // climb and a pause lets them fall back rather than freeze.
        energyRef.current = Math.max(0, target - 0.12);
        const next = current * 0.55 + target * 0.45;
        return next < 0.02 ? 0 : next;
      });
    }, 60);
    return () => window.clearInterval(id);
  }, [listening]);

  const start = useCallback(() => {
    const recognition = recognitionRef.current;
    if (!recognition) return;
    setError('');
    setTranscript('');
    setInterim('');
    energyRef.current = 0;
    wantListening.current = true;
    setStarting(true);
    try {
      recognition.start();
    } catch {
      // The engine throws InvalidStateError when it is already running —
      // after an auto-restart that this click raced, usually. Tear it down
      // and try once more rather than reporting a failure that is really a
      // double start. Swallowing this and claiming to be listening anyway
      // was the original bug: the bars moved over a dead recogniser.
      try {
        recognition.abort();
        recognition.start();
      } catch {
        wantListening.current = false;
        setStarting(false);
        setListening(false);
        setError('The microphone could not be started. Reload the page and try again.');
      }
    }
  }, []);

  const stop = useCallback(() => {
    wantListening.current = false;
    recognitionRef.current?.stop();
    setStarting(false);
    setListening(false);
  }, []);

  return { supported, listening, starting, transcript, interim, level, error, start, stop };
}
