import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  ApiClientError,
  clearAccessToken,
  fetchCurrentUser,
  getAccessToken,
  login as apiLogin,
  logoutSession,
  register as apiRegister,
  setAccessToken,
} from '../api/client';
import type { AuthUser } from '../types';

interface AuthState {
  user: AuthUser | null;
  checking: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Phase 16: minimal session state (no state-management library).
 * The JWT lives in localStorage (see client.ts tradeoff note); on load a
 * stored token is validated via /me, and any 401 anywhere clears it.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    let cancelled = false;
    if (!getAccessToken()) {
      setChecking(false);
      return;
    }
    fetchCurrentUser()
      .then((me) => {
        if (!cancelled) setUser(me);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          if (err instanceof ApiClientError && err.status === 401) clearAccessToken();
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) setChecking(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (email: string, password: string) => {
    const response = await apiLogin(email, password);
    setAccessToken(response.accessToken);
    setUser(response.user);
  }, []);

  const signUp = useCallback(async (email: string, password: string) => {
    const response = await apiRegister(email, password);
    setAccessToken(response.accessToken);
    setUser(response.user);
  }, []);

  /**
   * Task 3: end the session. The server revokes the refresh-token family
   * behind the HttpOnly cookie and clears that cookie; the local access JWT
   * is dropped regardless (best-effort — a failed logout call still ends the
   * local session, and the access JWT expires on its own within the hour).
   * The refresh token itself is never touched here: it is cookie-only.
   */
  const signOut = useCallback(async () => {
    try {
      await logoutSession();
    } catch {
      // Best-effort: local session ends even if the backend is unreachable.
    }
    clearAccessToken();
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, checking, signIn, signUp, signOut }),
    [user, checking, signIn, signUp, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error('useAuth must be used inside <AuthProvider>');
  return state;
}
