import { apiFetch, LONG_TIMEOUT_MS } from './client';
import type {
  ActionItemDto,
  AdjustButton,
  AdministratorsDto,
  AiSettingsDto,
  AiTestResult,
  UpdateAiSettingsRequest,
  Category,
  DashboardDto,
  EmailDetailDto,
  EmailAnalysisDto,
  EmailSummaryDto,
  GeneratedReplyDto,
  Page,
  Priority,
  RiskLevel,
  SyncStatusDto,
  ThreadMessageDto,
  UserDto,
} from '../types';

// --- Auth ---
export const AuthApi = {
  me: () => apiFetch<UserDto>('/api/auth/me'),
  disconnect: () => apiFetch<void>('/api/auth/disconnect', { method: 'POST' }),
  deleteAllData: () => apiFetch<void>('/api/auth/data', { method: 'DELETE' }),
  logout: () => apiFetch<void>('/api/auth/logout', { method: 'POST' }),
};

// --- Gmail sync ---
// Syncs run in the background on the server; results arrive over the
// realtime stream (see context/RealtimeContext.tsx).
export const GmailApi = {
  sync: () => apiFetch<{ status: 'STARTED' | 'ALREADY_RUNNING' }>('/api/gmail/sync', { method: 'POST' }),
  status: () => apiFetch<SyncStatusDto>('/api/gmail/status'),
};

// --- Dashboard ---
export const DashboardApi = {
  get: () => apiFetch<DashboardDto>('/api/dashboard'),
};

// --- Emails ---
export interface SearchFilters {
  sender?: string;
  keyword?: string;
  category?: Category;
  priority?: Priority;
  riskLevel?: RiskLevel;
  unreadOnly?: boolean;
}

export const EmailApi = {
  list: (page: number, size = 25, signal?: AbortSignal) =>
    apiFetch<Page<EmailSummaryDto>>('/api/emails', { params: { page, size }, signal }),

  search: (filters: SearchFilters, page: number, size = 25, signal?: AbortSignal) =>
    apiFetch<Page<EmailSummaryDto>>('/api/emails/search', { params: { ...filters, page, size }, signal }),

  get: (id: string, signal?: AbortSignal) => apiFetch<EmailDetailDto>(`/api/emails/${id}`, { signal }),

  delete: (id: string) => apiFetch<void>(`/api/emails/${id}`, { method: 'DELETE' }),

  analysis: (id: string, signal?: AbortSignal) =>
    apiFetch<EmailAnalysisDto | null>(`/api/emails/${id}/analysis`, { signal }),

  reanalyze: (id: string) =>
    apiFetch<EmailAnalysisDto>(`/api/emails/${id}/analyze`, { method: 'POST', timeoutMs: LONG_TIMEOUT_MS }),

  thread: (id: string) => apiFetch<ThreadMessageDto[]>(`/api/emails/${id}/thread`),

  replies: (id: string) => apiFetch<GeneratedReplyDto[]>(`/api/emails/${id}/replies`),

  generateReply: (id: string, instruction: string) =>
    apiFetch<GeneratedReplyDto>(`/api/emails/${id}/generate-reply`, {
      method: 'POST',
      body: { instruction },
      timeoutMs: LONG_TIMEOUT_MS,
    }),

  adjustReply: (id: string, previousDraftId: string, opts: { button?: AdjustButton; freeText?: string }) =>
    apiFetch<GeneratedReplyDto>(`/api/emails/${id}/replies/adjust`, {
      method: 'POST',
      body: { previousDraftId, button: opts.button, freeText: opts.freeText },
      timeoutMs: LONG_TIMEOUT_MS,
    }),

  sendReply: (id: string, draftId: string, finalBodyText: string, toAddress: string, subject: string) =>
    apiFetch<{ gmailMessageId: string }>(`/api/emails/${id}/send-reply`, {
      method: 'POST',
      body: { draftId, finalBodyText, toAddress, subject },
    }),
};

// --- Compose (brand-new email, not a reply) ---
export const ComposeApi = {
  generate: (toAddress: string, subject: string, instruction: string) =>
    apiFetch<GeneratedReplyDto>('/api/compose/generate', {
      method: 'POST',
      body: { toAddress, subject, instruction },
      timeoutMs: LONG_TIMEOUT_MS,
    }),

  adjust: (draftId: string, opts: { button?: AdjustButton; freeText?: string }) =>
    apiFetch<GeneratedReplyDto>('/api/compose/adjust', {
      method: 'POST',
      body: { previousDraftId: draftId, button: opts.button, freeText: opts.freeText },
      timeoutMs: LONG_TIMEOUT_MS,
    }),

  send: (draftId: string, finalBodyText: string, toAddress: string, subject: string) =>
    apiFetch<{ gmailMessageId: string }>('/api/compose/send', {
      method: 'POST',
      body: { draftId, finalBodyText, toAddress, subject },
    }),
};

// --- Admin: app-wide AI provider ---
export const AdminApi = {
  administrators: () => apiFetch<AdministratorsDto>('/api/admin/administrators'),

  aiSettings: () => apiFetch<AiSettingsDto>('/api/admin/ai-settings'),

  saveAiSettings: (body: UpdateAiSettingsRequest) =>
    apiFetch<AiSettingsDto>('/api/admin/ai-settings', { method: 'PUT', body }),

  resetAiSettings: () => apiFetch<AiSettingsDto>('/api/admin/ai-settings', { method: 'DELETE' }),

  testAiSettings: (body: UpdateAiSettingsRequest) =>
    apiFetch<AiTestResult>('/api/admin/ai-settings/test', { method: 'POST', body, timeoutMs: LONG_TIMEOUT_MS }),
};

// --- Action items ---
export const ActionItemApi = {
  list: (page: number, size = 50) =>
    apiFetch<Page<ActionItemDto>>('/api/action-items', { params: { page, size } }),

  setCompleted: (id: string, completed: boolean) =>
    apiFetch<ActionItemDto>(`/api/action-items/${id}`, { method: 'PATCH', body: { completed } }),
};
