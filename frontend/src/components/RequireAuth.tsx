import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { LoadingSpinner } from '../components/LoadingState';

/**
 * Phase 16: route guard. Unauthenticated visitors are bounced to /login with
 * the original destination preserved; nothing protected renders first.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, checking } = useAuth();
  const location = useLocation();

  if (checking) return <LoadingSpinner label="Checking your session…" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

/** Inverse guard: signed-in users visiting /login or /register go home. */
export function RedirectIfSignedIn({ children }: { children: ReactNode }) {
  const { user, checking } = useAuth();

  if (checking) return <LoadingSpinner label="Checking your session…" />;
  if (user) return <Navigate to="/" replace />;
  return <>{children}</>;
}
