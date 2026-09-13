import { useOutletContext } from 'react-router-dom';
import type { DashboardDto } from '../types';

/** Shared by every signed-in page through React Router's outlet context. */
export interface AppShellContext {
  /** Inbox counts for the navigation badges (null until loaded / when Gmail isn't connected). */
  stats: DashboardDto | null;
  refreshStats: () => void;
  openCompose: () => void;
  openShortcuts: () => void;
}

export function useAppShell(): AppShellContext {
  return useOutletContext<AppShellContext>();
}
