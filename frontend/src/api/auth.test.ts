import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearAccessToken,
  fetchCurrentUser,
  getAccessToken,
  login,
  register,
  setAccessToken,
} from './client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const AUTH_BODY = {
  tokenType: 'Bearer',
  accessToken: 'test-jwt-token',
  expiresIn: 3600,
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
});
