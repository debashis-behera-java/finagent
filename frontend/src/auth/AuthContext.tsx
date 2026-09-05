import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  ApiClientError,
  clearAccessToken,
  fetchCurrentUser,
  getAccessToken,
  login as apiLogin,
  register as apiRegister,
  setAccessToken,
} from '../api/client';
import type { AuthUser } from '../types';

interface AuthState {
  user: AuthUser | null;
  checking: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string) => Promise<void>;
  signOut: () => void;
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

  const signOut = useCallback(() => {
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
