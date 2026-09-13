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
  gmailConnected: boolean;
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
