import type { ApiErrorBody, AuthResponse, AuthUser, Research, ResearchAccepted, ResearchHistory } from '../types';

/**
 * Centralized backend API client (Phase 11). All HTTP lives here — components
 * and pages never call fetch directly.
 */
export class ApiClientError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | null;

  constructor(status: number, message: string, body: ApiErrorBody | null = null) {
    super(message);
    this.name = 'ApiClientError';
    this.status = status;
    this.body = body;
  }
}

function baseUrl(): string {
  // Empty/whitespace (e.g. Docker build with VITE_API_BASE_URL="") means
  // same-origin: the browser calls relative /api URLs (nginx reverse proxy).
  const raw = import.meta.env.VITE_API_BASE_URL as string | undefined;
  if (!raw || !raw.trim()) return '';
  return raw.replace(/\/$/, '');
}

/** Human-readable message for any API failure. Never surfaces stack traces. */
export function friendlyMessage(error: unknown): string {
  if (error instanceof ApiClientError) {
    if (error.status === 0) return 'Cannot reach the FinAgent backend. Start it with `mvn spring-boot:run` in backend/ and retry.';
    if (error.body?.message) return error.body.message;
    switch (error.status) {
      case 400: return 'The request was invalid. Check your input and try again.';
      case 401: return 'Your session has expired or you are not signed in. Please sign in again.';
      case 404: return 'The requested research was not found.';
      case 409: return 'The research is not completed yet — the PDF report is available once it completes.';
      case 429: return 'Too many requests — please slow down and try again in a minute.';
      case 500: return 'The backend hit an unexpected error. Please try again later.';
      default: return `Request failed (HTTP ${error.status}). Please try again.`;
    }
  }
  if (error instanceof Error) return error.message;
  return 'An unexpected error occurred. Please try again.';
}

// ---------- Phase 16: authentication (JWT Bearer) ----------

const TOKEN_KEY = 'finagent.accessToken';

/**
 * Token storage tradeoff (documented in docs/security.md): localStorage keeps
 * the SPA simple and survives reloads, but any XSS payload could read the
 * token — so the app renders all content as React text (no HTML sinks) and
 * uses short-lived (1h) access tokens with no refresh mechanism.
 */
export function getAccessToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAccessToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Private mode etc. — the in-memory session still works until reload.
  }
}

export function clearAccessToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

function authHeaders(init?: RequestInit): Record<string, string> {
  const token = getAccessToken();
  const base: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((init?.headers ?? {}) as Record<string, string>),
  };
  if (token) base['Authorization'] = `Bearer ${token}`;
  return base;
}

export async function register(email: string, password: string): Promise<AuthResponse> {
  return request<AuthResponse>('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  return request<AuthResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function fetchCurrentUser(): Promise<AuthUser> {
  return request<AuthUser>('/api/v1/auth/me');
}

async function request<T>(path: string, init?: RequestInit, timeoutMs = 15000): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}${path}`, {
      ...init,
      signal: controller.signal,
      headers: authHeaders(init),
    });
  } catch (error) {
    clearTimeout(timer);
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiClientError(0, 'The request timed out. The backend may be slow or unreachable.');
    }
    throw new ApiClientError(0, 'Cannot reach the FinAgent backend.');
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      body = null;
    }
    throw new ApiClientError(response.status, body?.message ?? `Request failed (HTTP ${response.status})`, body);
  }
  return (await response.json()) as T;
}

export interface CreateResearchInput {
  query: string;
  tickers?: string[];
}

export async function createResearch(input: CreateResearchInput): Promise<ResearchAccepted> {
  return request<ResearchAccepted>('/api/v1/research', {
    method: 'POST',
    body: JSON.stringify({ query: input.query, tickers: input.tickers ?? [] }),
  });
}

export async function getResearch(id: string): Promise<Research> {
  return request<Research>(`/api/v1/research/${encodeURIComponent(id)}`);
}

export async function getResearchHistory(page = 0, size = 10): Promise<ResearchHistory> {
  return request<ResearchHistory>(`/api/v1/research?page=${page}&size=${size}`);
}

export interface PdfDownload {
  blob: Blob;
  filename: string;
}

/** Downloads the generated PDF report (backend-generated; React never renders it). */
export async function downloadResearchPdf(id: string, timeoutMs = 30000): Promise<PdfDownload> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    const headers: Record<string, string> = {};
    const token = getAccessToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    response = await fetch(`${baseUrl()}/api/v1/research/${encodeURIComponent(id)}/report.pdf`, {
      signal: controller.signal,
      headers,
    });
  } catch (error) {
    clearTimeout(timer);
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiClientError(0, 'The PDF download timed out. Please try again.');
    }
    throw new ApiClientError(0, 'Cannot reach the FinAgent backend.');
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      body = null;
    }
    throw new ApiClientError(response.status, body?.message ?? `PDF download failed (HTTP ${response.status})`, body);
  }
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="([^"]+)"/.exec(disposition);
  return { blob, filename: match?.[1] ?? `finagent-research-${id}.pdf` };
}

/** Triggers a browser download for an already-fetched blob. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
