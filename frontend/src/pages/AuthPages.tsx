import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiClientError, friendlyMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { ErrorAlert } from '../components/Feedback';

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function validateEmail(email: string): string | null {
  if (!email.trim()) return 'Email must not be blank.';
  if (!EMAIL_PATTERN.test(email.trim())) return 'Enter a valid email address.';
  return null;
}

function validatePassword(password: string): string | null {
  if (password.length < 8) return 'Password must be at least 8 characters.';
  if (password.length > 72) return 'Password must be at most 72 characters.';
  if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
    return 'Password must contain at least one letter and one digit.';
  }
  return null;
}

/** Shared sign-in / registration form shell (Phase 16). */
export function AuthForm({
  mode,
  onSubmit,
}: {
  mode: 'login' | 'register';
  onSubmit: (email: string, password: string) => Promise<void>;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<string[]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const problems = [validateEmail(email), validatePassword(password)].filter(
      (p): p is string => p !== null,
    );
    setErrors(problems);
    setSubmitError(null);
    if (problems.length > 0) return;
    setSubmitting(true);
    try {
      await onSubmit(email.trim(), password);
    } catch (err) {
      if (err instanceof ApiClientError && err.body?.fieldErrors?.length) {
        setErrors(err.body.fieldErrors.map((f) => `${f.field}: ${f.message}`));
      } else {
        setSubmitError(friendlyMessage(err));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const title = mode === 'login' ? 'Sign in' : 'Create account';
  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-xl font-bold tracking-tight sm:text-2xl">{title}</h1>
      <p className="mt-1 text-sm text-slate-500">
        {mode === 'login'
          ? 'Sign in to access your research dashboard.'
          : 'Register for a FinAgent research account.'}
      </p>
      <form onSubmit={submit} className="mt-4 space-y-4" noValidate>
        <div>
          <label htmlFor="auth-email" className="block text-sm font-medium text-slate-700">
            Email
          </label>
          <input
            id="auth-email"
            type="email"
            autoComplete={mode === 'login' ? 'username' : 'email'}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
          />
        </div>
        <div>
          <label htmlFor="auth-password" className="block text-sm font-medium text-slate-700">
            Password
          </label>
          <input
            id="auth-password"
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none"
          />
          {mode === 'register' && (
            <p className="mt-1 text-xs text-slate-500">
              8–72 characters, at least one letter and one digit.
            </p>
          )}
        </div>
        {errors.length > 0 && (
          <ul className="list-disc space-y-1 pl-5 text-sm text-red-700" role="alert">
            {errors.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        )}
        {submitError && <ErrorAlert message={submitError} />}
        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-md bg-blue-700 px-4 py-2 text-sm font-medium text-white hover:bg-blue-800 disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        >
          {submitting ? 'Please wait…' : title}
        </button>
      </form>
      <p className="mt-4 text-sm text-slate-600">
        {mode === 'login' ? (
          <>
            No account yet? <Link to="/register" className="font-medium text-blue-700 hover:underline">Create one</Link>.
          </>
        ) : (
          <>
            Already registered? <Link to="/login" className="font-medium text-blue-700 hover:underline">Sign in</Link>.
          </>
        )}
      </p>
    </div>
  );
}

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  return (
    <AuthForm
      mode="login"
      onSubmit={async (email, password) => {
        await signIn(email, password);
        navigate(from, { replace: true });
      }}
    />
  );
}

export function RegisterPage() {
  const { signUp } = useAuth();
  const navigate = useNavigate();

  return (
    <AuthForm
      mode="register"
      onSubmit={async (email, password) => {
        await signUp(email, password);
        navigate('/', { replace: true });
      }}
    />
  );
}
