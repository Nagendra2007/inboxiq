import type { ApiErrorBody } from '../types';

/**
 * Base URL for the backend API. Empty means same-origin, which is the case
 * both in development (Vite proxies /api) and in the single-service
 * production image (Spring Boot serves this app). Only set
 * VITE_API_BASE_URL when the API genuinely lives on another origin.
 */
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '');

const DEFAULT_TIMEOUT_MS = 30_000;
/** AI generation and Gmail sync can legitimately take a while. */
export const LONG_TIMEOUT_MS = 120_000;

/** Fired whenever the backend answers 401, so the app can return to sign-in. */
export const UNAUTHORIZED_EVENT = 'inboxiq:unauthorized';

export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors?: Record<string, string>;

  constructor(status: number, body: Pick<ApiErrorBody, 'error' | 'message' | 'fieldErrors'>) {
    super(body.message || 'Request failed');
    this.name = 'ApiError';
    this.status = status;
    this.code = body.error;
    this.fieldErrors = body.fieldErrors;
  }

  /** No response at all (offline, DNS, server asleep) or a gateway error. */
  get isUnreachable(): boolean {
    return this.status === 0 || this.status === 502 || this.status === 503 || this.status === 504;
  }
}

/** Human message for any thrown value — never shows raw exceptions to users. */
export function errorMessage(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.message : fallback;
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError';
}

/** Reads the XSRF-TOKEN cookie Spring Security's CookieCsrfTokenRepository writes (readable by JS on purpose). */
function readCsrfCookie(): string | null {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT';

export interface RequestOptions {
  method?: Method;
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  timeoutMs?: number;
}

function buildQuery(params?: RequestOptions['params']): string {
  if (!params) return '';
  const usp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      usp.set(key, String(value));
    }
  }
  const qs = usp.toString();
  return qs ? `?${qs}` : '';
}

function fallbackMessage(status: number): string {
  if (status === 401) return 'Your session has ended. Please sign in again.';
  if (status === 403) return "You don't have permission to do that. Try refreshing the page.";
  if (status === 404) return 'That item no longer exists.';
  if (status === 429) return "You're going a little fast. Please wait a moment and try again.";
  if (status === 502 || status === 503 || status === 504) {
    return 'InboxIQ is starting up or briefly unavailable. Please try again in a moment.';
  }
  return 'Something went wrong. Please try again.';
}

/**
 * Thin fetch wrapper: always sends cookies (session auth), attaches the CSRF
 * header on mutating requests (the double-submit pattern SecurityConfig
 * expects), and normalizes every failure into an ApiError carrying a safe,
 * user-facing message.
 */
export function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return request<T>(path, options, true);
}

async function request<T>(path: string, options: RequestOptions, allowCsrfRetry: boolean): Promise<T> {
  const method = options.method || 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  let body: string | undefined;

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  const csrf = method === 'GET' ? null : readCsrfCookie();
  if (csrf) headers['X-XSRF-TOKEN'] = csrf;

  // Our own controller so a timeout and a caller's cancellation both abort.
  const controller = new AbortController();
  let timedOut = false;
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const forwardAbort = () => controller.abort();
  if (options.signal?.aborted) controller.abort();
  options.signal?.addEventListener('abort', forwardAbort);

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}${buildQuery(options.params)}`, {
      method,
      headers,
      body,
      credentials: 'include',
      signal: controller.signal,
    });
  } catch (err) {
    if (options.signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
    throw new ApiError(0, {
      error: timedOut ? 'TIMEOUT' : 'NETWORK_ERROR',
      message: timedOut
        ? 'The server took too long to respond. Please try again.'
        : "Can't reach InboxIQ right now. Check your connection and try again.",
    });
  } finally {
    window.clearTimeout(timer);
    options.signal?.removeEventListener('abort', forwardAbort);
  }

  // A 403 on a mutating request right after the CSRF cookie was (re)issued —
  // e.g. the very first request of a new session — succeeds on one retry.
  if (response.status === 403 && method !== 'GET' && allowCsrfRetry) {
    const fresh = readCsrfCookie();
    if (fresh && fresh !== csrf) return request<T>(path, options, false);
  }

  if (response.status === 204) return undefined as T;

  const isJson = (response.headers.get('content-type') || '').includes('application/json');
  let data: unknown;
  if (isJson) {
    try {
      data = await response.json();
    } catch {
      data = undefined;
    }
  }

  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    const errBody = (isJson && data && typeof data === 'object' ? data : {}) as Partial<ApiErrorBody>;
    throw new ApiError(response.status, {
      error: errBody.error || `HTTP_${response.status}`,
      message: errBody.message || fallbackMessage(response.status),
      fieldErrors: errBody.fieldErrors,
    });
  }

  return data as T;
}

/** Full-page navigation into Spring Security's OAuth2 login flow — this is a redirect, not a fetch. */
export function startGoogleLogin() {
  window.location.href = `${API_BASE}/oauth2/authorization/google`;
}
