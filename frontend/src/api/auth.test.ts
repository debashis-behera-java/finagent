import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearAccessToken,
  fetchCurrentUser,
  getAccessToken,
  login,
  logoutSession,
  refreshSession,
  register,
  setAccessToken,
} from './client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

const AUTH_BODY = {
  tokenType: 'Bearer',
  accessToken: 'test-jwt-token',
  expiresIn: 3600,
  // Task 3: the backend always answers refreshToken: null — the raw refresh
  // token travels only in the HttpOnly cookie, never in JSON.
  refreshToken: null,
  refreshExpiresIn: 1209600,
  user: { id: 'u1', email: 'a@example.com', role: 'USER', createdAt: null },
};

describe('auth client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('register posts credentials and returns the session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(201, AUTH_BODY));
    vi.stubGlobal('fetch', fetchMock);

    const session = await register('a@example.com', 'Secret123');

    expect(session.accessToken).toBe('test-jwt-token');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/auth/register');
    expect(JSON.parse(init.body as string)).toEqual({ email: 'a@example.com', password: 'Secret123' });
    // Registration payload never carries a role.
    expect(JSON.parse(init.body as string)).not.toHaveProperty('role');
  });

  it('login posts credentials and returns the session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, AUTH_BODY));
    vi.stubGlobal('fetch', fetchMock);

    const session = await login('a@example.com', 'Secret123');

    expect(session.user.email).toBe('a@example.com');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/login');
  });

  it('attaches the stored Bearer token to API requests', async () => {
    setAccessToken('stored-token');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, AUTH_BODY.user));
    vi.stubGlobal('fetch', fetchMock);

    await fetchCurrentUser();

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer stored-token');
  });

  it('sends no Authorization header without a token', async () => {
    clearAccessToken();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, AUTH_BODY.user));
    vi.stubGlobal('fetch', fetchMock);

    await fetchCurrentUser();

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined();
  });

  it('token helpers round-trip through storage', () => {
    expect(getAccessToken()).toBeNull();
    setAccessToken('abc');
    expect(getAccessToken()).toBe('abc');
    clearAccessToken();
    expect(getAccessToken()).toBeNull();
  });

  it('sends cookies (credentials: include) on auth calls', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, AUTH_BODY));
    vi.stubGlobal('fetch', fetchMock);

    await login('a@example.com', 'Secret123');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.credentials).toBe('include');
  });

  it('refreshSession posts to /refresh with no body and no manual token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, AUTH_BODY));
    vi.stubGlobal('fetch', fetchMock);

    const session = await refreshSession();

    expect(session.accessToken).toBe('test-jwt-token');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/auth/refresh');
    expect(init.method).toBe('POST');
    // The browser attaches the HttpOnly cookie itself — JS sends nothing.
    expect(init.body).toBeUndefined();
    expect(init.credentials).toBe('include');
  });

  it('logoutSession posts to /logout and tolerates the empty 204', async () => {
    const fetchMock = vi.fn().mockResolvedValue(emptyResponse(204));
    vi.stubGlobal('fetch', fetchMock);

    await expect(logoutSession()).resolves.toBeUndefined();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/auth/logout');
    expect(init.method).toBe('POST');
    expect(init.body).toBeUndefined();
    expect(init.credentials).toBe('include');
  });

  it('never places refresh tokens in JavaScript-accessible storage', async () => {
    // Fresh Response per call: a body can only be consumed once.
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, AUTH_BODY)));
    vi.stubGlobal('fetch', fetchMock);

    await login('a@example.com', 'Secret123');
    await refreshSession();
    // The AuthContext persists only the access JWT after sign-in.
    setAccessToken('test-jwt-token');

    // Only the access-token key may exist; no refresh material anywhere.
    // (A leaked refresh value would look like this — it must never appear.)
    const leakedRefresh = 'leaked-refresh-token-value';
    expect(localStorage.getItem('finagent.accessToken')).toBe('test-jwt-token');
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i) ?? '';
      expect(key.toLowerCase()).not.toContain('refresh');
      expect(localStorage.getItem(key) ?? '').not.toContain(leakedRefresh);
    }
    // No outgoing request body ever carries a refresh token.
    for (const [, init] of fetchMock.mock.calls as Array<[string, RequestInit]>) {
      expect((init.body as string | undefined) ?? '').not.toContain('refreshToken');
    }
  });
});
