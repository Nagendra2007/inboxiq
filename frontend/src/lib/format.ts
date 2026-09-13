const MINUTE = 60_000;
const DAY = 86_400_000;

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/** Gmail-style list timestamp: time today, "Yesterday", weekday this week, then a date. */
export function formatListTime(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  const now = new Date();
  if (now.getTime() - date.getTime() < MINUTE) return 'Just now';
  const dayDiff = Math.round((startOfDay(now).getTime() - startOfDay(date).getTime()) / DAY);
  if (dayDiff <= 0) return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  if (dayDiff === 1) return 'Yesterday';
  if (dayDiff < 7) return date.toLocaleDateString(undefined, { weekday: 'short' });
  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

export function formatFullDate(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

export function formatTimeAgo(date: Date): string {
  const diff = Date.now() - date.getTime();
  if (diff < MINUTE) return 'just now';
  const minutes = Math.floor(diff / MINUTE);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export interface ParsedSender {
  name: string;
  email: string;
}

/** Splits `"Jane Doe" <jane@example.com>` into its display name and address. */
export function parseSender(sender: string | null): ParsedSender {
  if (!sender) return { name: 'Unknown sender', email: '' };
  const angle = sender.match(/^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$/);
  if (angle) {
    const email = angle[2].trim();
    const name = angle[1].trim();
    return { name: name || email.split('@')[0], email };
  }
  const bare = sender.trim();
  if (bare.includes('@')) return { name: bare.split('@')[0], email: bare };
  return { name: bare, email: '' };
}

export function initials(name: string): string {
  const parts = name.replace(/[^\p{L}\p{N}\s]/gu, ' ').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** "NEWSLETTER" -> "Newsletter" */
export function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count.toLocaleString()} ${count === 1 ? singular : plural}`;
}

export function formatCount(value: number): string {
  return value >= 10_000
    ? new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value)
    : value.toLocaleString();
}

export function firstName(name: string | null | undefined): string | null {
  if (!name) return null;
  return name.trim().split(/\s+/)[0] || null;
}

export function greeting(now = new Date()): string {
  const hour = now.getHours();
  if (hour < 5) return 'Working late';
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

export type DeadlineGroup = 'overdue' | 'today' | 'week' | 'later' | 'none';

export interface DeadlineInfo {
  label: string;
  group: DeadlineGroup;
}

/**
 * Deadlines arrive as ISO calendar dates ("2026-09-13"). They're parsed as
 * local dates on purpose: `new Date("2026-09-13")` means UTC midnight, which
 * shows as the previous day anywhere west of Greenwich.
 */
export function deadlineInfo(deadline: string | null, now = new Date()): DeadlineInfo {
  if (!deadline) return { label: 'No deadline', group: 'none' };
  const [y, m, d] = deadline.split('-').map(Number);
  if (!y || !m || !d) return { label: deadline, group: 'later' };
  const due = new Date(y, m - 1, d);
  const days = Math.round((due.getTime() - startOfDay(now).getTime()) / DAY);
  const dateLabel = due.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    ...(due.getFullYear() !== now.getFullYear() ? { year: 'numeric' as const } : {}),
  });
  if (days < 0) return { label: days === -1 ? 'Due yesterday' : `Overdue · ${dateLabel}`, group: 'overdue' };
  if (days === 0) return { label: 'Due today', group: 'today' };
  if (days === 1) return { label: 'Due tomorrow', group: 'week' };
  if (days < 7) return { label: `Due ${due.toLocaleDateString(undefined, { weekday: 'long' })}`, group: 'week' };
  return { label: `Due ${dateLabel}`, group: 'later' };
}

export function isValidEmail(value: string): boolean {
  return /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(value.trim());
}
