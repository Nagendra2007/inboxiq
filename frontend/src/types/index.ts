// Mirrors the backend's com.inboxiq.dto.* records exactly. Keeping these in
// one file makes it obvious when the frontend and backend contracts drift.

export type Category =
  | 'PERSONAL' | 'WORK' | 'EDUCATION' | 'FINANCE' | 'SHOPPING' | 'DELIVERY'
  | 'SECURITY' | 'SOCIAL' | 'MARKETING' | 'NEWSLETTER' | 'SUSPICIOUS' | 'OTHER';

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type AnalysisStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface UserDto {
  id: string;
  email: string;
  name: string | null;
  /** A Gmail account is linked (not disconnected by the user). */
  gmailConnected: boolean;
  /** Google rejected InboxIQ's Gmail access: still signed in, but Gmail must be reconnected. */
  gmailReauthRequired: boolean;
  /** May change app-wide settings (the AI provider). */
  admin: boolean;
}

/** Persisted Gmail sync state (GET /api/gmail/status). */
export interface SyncStatusDto {
  syncing: boolean;
  initialSyncCompleted: boolean;
  lastSyncAt: string | null;
  lastError: string | null;
  reauthRequired: boolean;
}

// --- Realtime (Server-Sent Events from GET /api/events) ---
export type SyncTrigger = 'LOGIN' | 'CONNECT' | 'POLL' | 'MANUAL';

export interface SyncStartedEvent {
  trigger: SyncTrigger;
  initial: boolean;
}

export interface SyncCompletedEvent {
  trigger: SyncTrigger;
  mode: 'INITIAL' | 'INCREMENTAL' | 'CATCH_UP';
  initial: boolean;
  newEmails: number;
  updatedEmails: number;
  removedEmails: number;
  lastSyncAt: string;
}

export interface SyncErrorEvent {
  code: string;
  message: string;
  reauthRequired: boolean;
}

export interface AnalysisEvent {
  emailId: string;
  /** Absent on analysis.started; the rule-based fallback on analysis.failed. */
  analysis?: EmailAnalysisDto | null;
}

export interface RealtimeEventMap {
  connected: { serverTime: string };
  'sync.started': SyncStartedEvent;
  'sync.completed': SyncCompletedEvent;
  'sync.error': SyncErrorEvent;
  'email.received': { gmailMessageId: string };
  'email.saved': { email: EmailSummaryDto };
  'email.updated': { id: string; read: boolean };
  'email.deleted': { id: string };
  'email.analysis.started': AnalysisEvent;
  'email.analysis.completed': AnalysisEvent;
  'email.analysis.failed': AnalysisEvent;
  /** Client-side: the stream (re)opened, so events may have been missed — refetch. */
  resync: Record<string, never>;
}

/** Who the administrators are, and which rule decided it. */
export interface AdministratorsDto {
  source: 'ADMIN_EMAILS' | 'FIRST_ACCOUNT';
  admins: { email: string; signedIn: boolean }[];
}

export interface AiProviderOption {
  id: string;
  label: string;
  keyUrl: string | null;
  suggestedModels: string[];
  requiresBaseUrl: boolean;
}

/** App-wide AI configuration as shown to the admin — never includes the key itself. */
export interface AiSettingsDto {
  provider: string;
  providerLabel: string;
  model: string | null;
  baseUrl: string | null;
  hasApiKey: boolean;
  apiKeyHint: string | null;
  usingEnvironmentKey: boolean;
  source: 'APP' | 'ENVIRONMENT';
  updatedBy: string | null;
  updatedAt: string | null;
  environmentDefault: { provider: string; providerLabel: string; model: string | null; hasApiKey: boolean };
  providers: AiProviderOption[];
}

export interface UpdateAiSettingsRequest {
  provider: string;
  model: string;
  apiKey?: string;
  baseUrl?: string;
}

export interface AiTestResult {
  ok: boolean;
  message: string;
  latencyMs: number;
}

export interface EmailAnalysisDto {
  summary: string | null;
  keyPoints: string[];
  category: Category | null;
  priority: Priority | null;
  priorityScore: number | null;
  riskScore: number | null;
  riskLevel: RiskLevel | null;
  riskReasons: string[];
  requiresReply: boolean | null;
  actionRequired: boolean | null;
  importantDates: string[];
  analysisStatus: AnalysisStatus | null;
  failureReason: string | null;
}

export interface EmailSummaryDto {
  id: string;
  sender: string | null;
  subject: string | null;
  snippet: string | null;
  receivedAt: string | null;
  read: boolean;
  hasAttachments: boolean;
  analysis: EmailAnalysisDto | null;
}

export interface EmailDetailDto {
  id: string;
  sender: string | null;
  recipient: string | null;
  ccRecipient: string | null;
  subject: string | null;
  snippet: string | null;
  bodyText: string | null;
  bodyHtml: string | null;
  receivedAt: string | null;
  read: boolean;
  hasAttachments: boolean;
  threadId: string | null;
  analysis: EmailAnalysisDto | null;
  actionItems: ActionItemDto[];
}

export interface ActionItemDto {
  id: string;
  emailId: string;
  emailSubject: string | null;
  description: string;
  deadline: string | null;
  completed: boolean;
}

export interface GeneratedReplyDto {
  id: string;
  /** Null for a from-scratch compose draft (no source email). */
  emailId: string | null;
  userPrompt: string;
  content: string;
  toneAdjustment: string | null;
  sent: boolean;
  sentAt: string | null;
}

export interface DashboardDto {
  totalEmails: number;
  unreadEmails: number;
  receivedLast7Days: number;
  highPriority: number;
  mediumPriority: number;
  lowPriority: number;
  highRisk: number;
  mediumRisk: number;
  awaitingReply: number;
  openActionItems: number;
}

export interface ThreadMessageDto {
  messageId: string;
  sender: string | null;
  subject: string | null;
  snippet: string | null;
  bodyText: string | null;
  receivedAt: string | null;
  unread: boolean;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface ApiErrorBody {
  timestamp: string;
  error: string;
  message: string;
  fieldErrors?: Record<string, string>;
}

export type AdjustButton = 'MAKE_SHORTER' | 'MAKE_FORMAL' | 'MAKE_FRIENDLY' | 'REGENERATE';
