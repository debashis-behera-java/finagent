/**
 * Frontend types mirroring the backend DTOs (Phase 11).
 * These describe API responses only — no business calculations live here.
 */

/** Phase 16: authenticated principal (mirrors UserDto — never any password material). */
export interface AuthUser {
  id: string;
  email: string;
  role: 'USER' | 'ADMIN';
  createdAt: string | null;
}

/** Phase 16: register/login response (mirrors AuthResponseDto). */
export interface AuthResponse {
  tokenType: string;
  accessToken: string;
  expiresIn: number;
  user: AuthUser;
}

export type ResearchStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ResearchAccepted {
  researchId: string;
  status: ResearchStatus;
  createdAt: string;
}

export interface ResearchError {
  code: string;
  message: string;
}

export interface RiskSnapshot {
  volatility?: number | null;
  maxDrawdown?: number | null;
  beta?: number | null;
  sharpe?: number | null;
  score?: number | null;
  category?: string | null;
}

export interface SentimentSnapshot {
  status: 'available' | 'unavailable' | 'not-analyzed' | string;
  label?: string | null;
  score?: number | null;
  confidence?: number | null;
  reason?: string | null;
  articleCount?: number;
  analyzedCount?: number;
  unavailableCount?: number;
  positiveCount?: number;
  neutralCount?: number;
  negativeCount?: number;
  methodology?: string | null;
}

export interface MetricsEntry {
  symbol: string;
  price?: number | null;
  change?: number | null;
  changePercent?: number | null;
  companyName?: string | null;
  sector?: string | null;
  industry?: string | null;
  marketCap?: number | null;
  peRatio?: number | null;
  dividendYield?: number | null;
  eps?: number | null;
  historyBars?: number;
  newsCount?: number;
  headlines?: string[];
  risk?: RiskSnapshot | null;
  sentiment?: SentimentSnapshot | null;
  gaps?: string[];
}

export interface MetricsSnapshot {
  tickers?: string[];
  generatedAt?: string;
  entries?: MetricsEntry[];
  guard?: { passed?: boolean; redactedCount?: number };
  disclaimer?: string;
}

export interface ResearchResult {
  tickers: string[];
  executiveSummary?: string | null;
  interpretation?: string | null;
  metrics?: MetricsSnapshot | null;
  newsSummary?: string | null;
  disclaimer?: string | null;
  completedAt?: string | null;
}

export interface Research {
  researchId: string;
  status: ResearchStatus;
  createdAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  requestText?: string | null;
  tickers?: string[];
  result?: ResearchResult | null;
  error?: ResearchError | null;
}

export interface ResearchHistory {
  content: Research[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
}
