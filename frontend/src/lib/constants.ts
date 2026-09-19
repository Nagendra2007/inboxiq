/**
 * How many emails one inbox page holds. Shared with api/prefetch.ts, which
 * asks for the first page before React mounts — the sizes have to match or
 * the prefetched page is the wrong one.
 */
export const INBOX_PAGE_SIZE = 40;
