import { EmailApi } from './endpoints';
import type { EmailSummaryDto, Page } from '../types';

/**
 * Asks for the first page of the inbox as soon as the bundle runs, instead
 * of waiting for React to mount, /api/auth/me to answer and InboxPage to
 * decide it needs data. Those requests don't depend on each other, so this
 * turns two round trips into one — worth a visible fraction of a second on
 * a small instance, which is exactly the wait between signing in and seeing
 * mail.
 *
 * Nothing here changes what is shown: if the request fails, arrives after
 * the page mounted, or the visitor turns out to be signed out, InboxPage
 * just loads normally.
 */
let inFlight: Promise<Page<EmailSummaryDto>> | null = null;

export function prefetchInbox(size: number): void {
  // Nobody signed in to prefetch for — and a 401 here would be noise.
  if (inFlight || window.location.pathname === '/login') return;
  inFlight = EmailApi.list(0, size);
  // The real handler is attached when InboxPage picks this up, which may be
  // after it settles; this keeps a failure from looking unhandled.
  inFlight.catch(() => {});
}

/** The prefetched first page, once. Null once used, or if none was started. */
export function takePrefetchedInbox(): Promise<Page<EmailSummaryDto>> | null {
  const promise = inFlight;
  inFlight = null;
  return promise;
}
