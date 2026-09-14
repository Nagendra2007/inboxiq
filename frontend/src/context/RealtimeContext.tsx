import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { apiUrl } from '../api/client';
import { useAuth } from './AuthContext';
import type { RealtimeEventMap } from '../types';

/**
 * The app's one realtime connection: a single EventSource on GET /api/events
 * for the whole signed-in app (never one per email or per page). The session
 * cookie authenticates it and the server only sends this user's events.
 *
 * Reconnection: after a network blip the browser reconnects on its own. If
 * the server refuses the stream (restarting, or the session ended), this
 * retries with backoff and re-checks sign-in. Every time the stream opens —
 * the first time too, since a sync started at sign-in may already have sent
 * events before this page connected — a client-side `resync` event tells
 * pages to refetch whatever they show.
 */

type EventName = keyof RealtimeEventMap;
type Listener<K extends EventName> = (data: RealtimeEventMap[K]) => void;

/** open: live · connecting: first connection · reconnecting: live updates paused */
export type RealtimeStatus = 'connecting' | 'open' | 'reconnecting' | 'off';

const SERVER_EVENTS: Exclude<EventName, 'resync'>[] = [
  'connected',
  'sync.started',
  'sync.completed',
  'sync.error',
  'email.received',
  'email.saved',
  'email.updated',
  'email.deleted',
  'email.analysis.started',
  'email.analysis.completed',
  'email.analysis.failed',
];

const MAX_BACKOFF_MS = 30_000;

interface RealtimeValue {
  status: RealtimeStatus;
  subscribe: <K extends EventName>(event: K, listener: Listener<K>) => () => void;
}

const noopValue: RealtimeValue = { status: 'off', subscribe: () => () => {} };
const RealtimeContext = createContext<RealtimeValue>(noopValue);

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const { status: authStatus, user, refresh } = useAuth();
  const enabled = authStatus === 'authenticated' && !!user?.gmailConnected;
  const [status, setStatus] = useState<RealtimeStatus>('off');
  const listeners = useRef(new Map<EventName, Set<(data: unknown) => void>>());
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  const emit = useCallback((event: EventName, data: unknown) => {
    listeners.current.get(event)?.forEach((listener) => {
      try {
        listener(data);
      } catch (err) {
        console.error(`Realtime listener for ${event} failed`, err);
      }
    });
  }, []);

  const subscribe = useCallback(<K extends EventName>(event: K, listener: Listener<K>) => {
    const set = listeners.current.get(event) ?? new Set();
    const wrapped = listener as (data: unknown) => void;
    set.add(wrapped);
    listeners.current.set(event, set);
    return () => {
      set.delete(wrapped);
    };
  }, []);

  useEffect(() => {
    if (!enabled || typeof EventSource === 'undefined') {
      setStatus('off');
      return;
    }
    let source: EventSource | null = null;
    let retryTimer: number | undefined;
    let attempt = 0;
    let everOpened = false;
    let disposed = false;

    const connect = () => {
      if (disposed) return;
      window.clearTimeout(retryTimer);
      source?.close();
      setStatus(everOpened ? 'reconnecting' : 'connecting');

      const stream = new EventSource(apiUrl('/api/events'), { withCredentials: true });
      source = stream;
      stream.onopen = () => {
        attempt = 0;
        everOpened = true;
        setStatus('open');
        emit('resync', {});
      };
      stream.onerror = () => {
        if (stream.readyState !== EventSource.CLOSED) {
          setStatus('reconnecting'); // the browser is already retrying
          return;
        }
        // Refused outright (server restarting, or signed out): retry
        // ourselves, and let the auth check send a signed-out user to /login.
        stream.close();
        setStatus('reconnecting');
        refreshRef.current();
        const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** attempt);
        attempt += 1;
        retryTimer = window.setTimeout(connect, delay);
      };
      for (const name of SERVER_EVENTS) {
        stream.addEventListener(name, (event) => {
          let data: unknown = {};
          try {
            data = JSON.parse((event as MessageEvent<string>).data);
          } catch {
            // Keep {} for an unparsable payload.
          }
          emit(name, data);
        });
      }
    };

    // Coming back to a tab whose stream gave up: reconnect now, not after the backoff.
    const onVisible = () => {
      if (document.visibilityState === 'visible' && source?.readyState === EventSource.CLOSED) {
        attempt = 0;
        connect();
      }
    };

    connect();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      disposed = true;
      window.clearTimeout(retryTimer);
      document.removeEventListener('visibilitychange', onVisible);
      source?.close();
    };
  }, [enabled, emit]);

  const value = useMemo(() => ({ status, subscribe }), [status, subscribe]);
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>;
}

export function useRealtime(): RealtimeValue {
  return useContext(RealtimeContext);
}

/** Runs `handler` for every `event`, always calling the latest handler without resubscribing. */
export function useRealtimeEvent<K extends EventName>(event: K, handler: Listener<K>) {
  const { subscribe } = useRealtime();
  const handlerRef = useRef(handler);
  handlerRef.current = handler;
  useEffect(() => subscribe(event, (data) => handlerRef.current(data)), [event, subscribe]);
}
