import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AuthApi } from '../api/endpoints';
import { ApiError, UNAUTHORIZED_EVENT } from '../api/client';
import type { UserDto } from '../types';

/**
 * - loading: first /me request in flight
 * - authenticated / unauthenticated: the backend answered
 * - unreachable: no usable answer (offline, or a sleeping free-tier server).
 *   Kept distinct from "unauthenticated" so a slow cold start doesn't
 *   masquerade as being signed out.
 */
export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'unreachable';

interface AuthContextValue {
  user: UserDto | null;
  status: AuthStatus;
  /** True when a signed-in session ended mid-use (a 401 from any API call). */
  sessionExpired: boolean;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [sessionExpired, setSessionExpired] = useState(false);
  const statusRef = useRef(status);
  statusRef.current = status;

  const refresh = useCallback(async () => {
    try {
      const me = await AuthApi.me();
      setUser(me);
      setStatus('authenticated');
      setSessionExpired(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setUser(null);
        setStatus('unauthenticated');
      } else if (statusRef.current !== 'authenticated') {
        // An already signed-in user stays signed in through a transient
        // failure; only the initial check reports "unreachable".
        setStatus('unreachable');
      }
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    const onUnauthorized = () => {
      if (statusRef.current === 'authenticated') setSessionExpired(true);
      setUser(null);
      setStatus('unauthenticated');
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, []);

  const value = useMemo(() => ({ user, status, sessionExpired, refresh }), [user, status, sessionExpired, refresh]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
