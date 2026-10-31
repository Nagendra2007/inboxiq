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
  const [transcript, setTranscript] = useState('');
  const [interim, setInterim] = useState('');
  const [level, setLevel] = useState(0);
  const [error, setError] = useState('');

  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  // What the user wants, as opposed to what the engine is currently doing.
  // The two drift apart constantly, which is the whole problem below.
  const wantListening = useRef(false);

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
    };

    recognition.onerror = (event) => {
      const message = describe(event.error);
      if (message) setError(message);
      if (FATAL.has(event.error)) {
        wantListening.current = false;
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
        setListening(false);
        return;
      }
      try {
        recognition.start();
      } catch {
        wantListening.current = false;
        setListening(false);
      }
    };

    recognitionRef.current = recognition;
    return () => {
      wantListening.current = false;
      recognition.onresult = null;
      recognition.onerror = null;
      recognition.onend = null;
      recognition.abort();
      recognitionRef.current = null;
    };
  }, [lang]);

  // A level meter off a second microphone stream. The recogniser keeps its
  // own stream and does not share it, but browsers allow several readers of
  // one device. Entirely cosmetic, so every failure here is swallowed: a
  // flat meter is better than a broken dictation box.
  useEffect(() => {
    if (!listening) {
      setLevel(0);
      return;
    }
    let stream: MediaStream | null = null;
    let audio: AudioContext | null = null;
    let frame = 0;
    let stopped = false;
    let last = 0;

    navigator.mediaDevices
      ?.getUserMedia({ audio: true })
      .then((granted) => {
        if (stopped) {
          granted.getTracks().forEach((t) => t.stop());
          return;
        }
        stream = granted;
        const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
        audio = new Ctx();
        const analyser = audio.createAnalyser();
        analyser.fftSize = 512;
        audio.createMediaStreamSource(granted).connect(analyser);
        const samples = new Uint8Array(analyser.frequencyBinCount);

        const tick = () => {
          analyser.getByteTimeDomainData(samples);
          let sum = 0;
          for (let i = 0; i < samples.length; i++) {
            const deviation = (samples[i] - 128) / 128;
            sum += deviation * deviation;
          }
          const rms = Math.sqrt(sum / samples.length);
          // Speech sits low in this range, so lift it before clamping, then
          // smooth: a meter that tracks every frame exactly looks like noise.
          const scaled = Math.min(1, rms * 4);
          const next = scaled > last ? scaled : last * 0.82 + scaled * 0.18;
          // ~20fps of state is plenty for the eye and cheap for React.
          if (Math.abs(next - last) > 0.02) {
            last = next;
            setLevel(next);
          }
          frame = requestAnimationFrame(tick);
        };
        frame = requestAnimationFrame(tick);
      })
      .catch(() => {
        /* No meter. Dictation still works. */
      });

    return () => {
      stopped = true;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((t) => t.stop());
      audio?.close().catch(() => {});
    };
  }, [listening]);

  const start = useCallback(() => {
    const recognition = recognitionRef.current;
    if (!recognition) return;
    setError('');
    setTranscript('');
    setInterim('');
    wantListening.current = true;
    try {
      recognition.start();
      setListening(true);
    } catch {
      // Already running: the browser throws rather than no-opping.
      setListening(true);
    }
  }, []);

  const stop = useCallback(() => {
    wantListening.current = false;
    recognitionRef.current?.stop();
    setListening(false);
  }, []);

  return { supported, listening, transcript, interim, level, error, start, stop };
}
