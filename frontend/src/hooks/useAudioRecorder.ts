import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Records a short clip from the microphone, with a level meter off the same
 * stream.
 *
 * This replaced the browser's own speech recognition, which in Chrome is a
 * call to a Google service that answers with silence — no result, no usable
 * error — when it cannot be reached or does not like the audio, and which
 * Firefox does not implement at all. Recording and transcribing server-side
 * works everywhere, fails with a real message, and is noticeably better on
 * names.
 *
 * The meter can read this stream directly, because there is no longer a
 * recogniser holding the microphone to compete with. That was the bug in the
 * previous version: a second capture stream quietly starved the recogniser
 * while the bars kept moving, so the interface insisted it was working.
 */

export interface AudioRecorderState {
  supported: boolean;
  recording: boolean;
  /** True between pressing record and the microphone actually opening. */
  starting: boolean;
  /** Loudness now, 0 to 1. Flat bars mean the microphone is not picking you up. */
  level: number;
  /** Seconds recorded so far. */
  seconds: number;
  error: string;
  start: () => void;
  /** Resolves with the clip, or null if nothing was captured. */
  stop: () => Promise<Blob | null>;
  cancel: () => void;
}

/** Opus in WebM is what Chrome and Firefox both produce; Safari gives mp4. */
/**
 * Opens the microphone, twice if necessary.
 *
 * <p>Browser-side cleanup helps the transcriber as much as it helps a
 * call: less room noise is less for the model to guess at. But asking for
 * echo cancellation makes Chrome request a voice-communication audio
 * source, and plenty of Android devices will not hand that out to a
 * WebView — the device refuses to open at all and reports
 * NotReadableError, which reads as "your microphone is broken".
 *
 * <p>Cleaner audio is not worth no audio, so a refusal is retried with no
 * processing requested. Whisper copes with room noise far better than it
 * copes with silence.
 */
async function openMicrophone(): Promise<MediaStream> {
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
  } catch (err) {
    const name = (err as DOMException)?.name;
    if (name !== 'NotReadableError' && name !== 'OverconstrainedError' && name !== 'AbortError') {
      throw err;
    }
    return navigator.mediaDevices.getUserMedia({ audio: true });
  }
}

function pickMimeType(): string | undefined {
  if (typeof MediaRecorder === 'undefined') return undefined;
  for (const type of ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']) {
    if (MediaRecorder.isTypeSupported(type)) return type;
  }
  return undefined;
}

export function useAudioRecorder(maxSeconds = 60): AudioRecorderState {
  const [supported] = useState(
    () => typeof window !== 'undefined' && typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices
  );
  const [recording, setRecording] = useState(false);
  const [starting, setStarting] = useState(false);
  const [level, setLevel] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const [error, setError] = useState('');

  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<BlobPart[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const audioRef = useRef<AudioContext | null>(null);
  const frameRef = useRef(0);
  const tickRef = useRef<number | undefined>(undefined);

  const teardown = useCallback(() => {
    cancelAnimationFrame(frameRef.current);
    window.clearInterval(tickRef.current);
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    audioRef.current?.close().catch(() => {});
    audioRef.current = null;
    recorderRef.current = null;
    setLevel(0);
  }, []);

  useEffect(() => teardown, [teardown]);

  const start = useCallback(async () => {
    if (!supported) return;
    setError('');
    setSeconds(0);
    chunksRef.current = [];
    setStarting(true);
    // Nothing from a previous attempt should still hold the device: a
    // stream left open is itself a reason the next one cannot be.
    teardown();

    try {
      const stream = await openMicrophone();
      streamRef.current = stream;

      // Android's WebView supports fewer container formats than Chrome
      // does, and passing one it does not know throws NotSupportedError
      // from the constructor — after the microphone has already opened,
      // which is why that reads as the microphone failing. With no type at
      // all it picks its own, and the transcriber accepts what it sends.
      const mimeType = pickMimeType();
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.start();
      recorderRef.current = recorder;
      setStarting(false);
      setRecording(true);

      // Meter, off the same stream the recorder is using.
      const Ctx =
        window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const audio = new Ctx();
      audioRef.current = audio;
      const analyser = audio.createAnalyser();
      analyser.fftSize = 512;
      audio.createMediaStreamSource(stream).connect(analyser);
      const samples = new Uint8Array(analyser.frequencyBinCount);
      let last = 0;

      const draw = () => {
        analyser.getByteTimeDomainData(samples);
        let sum = 0;
        for (let i = 0; i < samples.length; i++) {
          const deviation = (samples[i] - 128) / 128;
          sum += deviation * deviation;
        }
        // Speech sits low in this range, so lift before clamping, then
        // smooth on the way down only: rising instantly reads as responsive,
        // falling instantly reads as flicker.
        const scaled = Math.min(1, Math.sqrt(sum / samples.length) * 4);
        const next = scaled > last ? scaled : last * 0.82 + scaled * 0.18;
        if (Math.abs(next - last) > 0.02) {
          last = next;
          setLevel(next);
        }
        frameRef.current = requestAnimationFrame(draw);
      };
      frameRef.current = requestAnimationFrame(draw);

      tickRef.current = window.setInterval(() => {
        setSeconds((s) => {
          if (s + 1 >= maxSeconds) recorderRef.current?.stop();
          return s + 1;
        });
      }, 1000);
    } catch (err) {
      setStarting(false);
      setRecording(false);
      const name = (err as DOMException)?.name;
      switch (name) {
        case 'NotAllowedError':
          setError('Microphone access was blocked. Allow it, then try again.');
          break;
        case 'NotFoundError':
          setError('No microphone was found.');
          break;
        case 'NotReadableError':
          // Reached only when the plain retry failed too, so this really
          // is the device being unavailable rather than the processing
          // being refused.
          setError('The microphone could not be opened. Close anything else using it and try again.');
          break;
        case 'OverconstrainedError':
          setError('This microphone cannot record in the format Mailroom needs.');
          break;
        default:
          // Everything else names itself. A single catch-all message is how
          // the real cause gets thrown away, and then the only way to find
          // it is to guess.
          setError(
            name
              ? `The microphone could not be opened (${name}).`
              : 'The microphone could not be opened.'
          );
      }
    }
  }, [supported, maxSeconds]);

  const stop = useCallback((): Promise<Blob | null> => {
    const recorder = recorderRef.current;
    if (!recorder || recorder.state === 'inactive') {
      teardown();
      setRecording(false);
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      recorder.onstop = () => {
        const type = recorder.mimeType || 'audio/webm';
        const blob = chunksRef.current.length ? new Blob(chunksRef.current, { type }) : null;
        teardown();
        setRecording(false);
        resolve(blob);
      };
      recorder.stop();
    });
  }, [teardown]);

  const cancel = useCallback(() => {
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      recorder.onstop = null;
      recorder.stop();
    }
    chunksRef.current = [];
    teardown();
    setRecording(false);
    setSeconds(0);
  }, [teardown]);

  return { supported, recording, starting, level, seconds, error, start: () => void start(), stop, cancel };
}
