import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { RequireAuth } from '../components/RequireAuth';
import { LoginPage, RegisterPage } from './AuthPages';
import * as api from '../api/client';

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>();
  return { ...original, login: vi.fn(), register: vi.fn(), fetchCurrentUser: vi.fn() };
});

const mockedLogin = vi.mocked(api.login);
const mockedRegister = vi.mocked(api.register);
const mockedMe = vi.mocked(api.fetchCurrentUser);

function renderLogin() {
  render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('AuthPages', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
  });

  it('rejects invalid email and weak password without calling the API', async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText(/email/i), 'not-an-email');
    await user.type(screen.getByLabelText(/password/i), 'short');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
    expect(await screen.findByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(mockedLogin).not.toHaveBeenCalled();
  });

  it('signs in and navigates home on success', async () => {
    const user = userEvent.setup();
    mockedLogin.mockResolvedValue({
      tokenType: 'Bearer',
      accessToken: 'tok',
      expiresIn: 3600,
      user: { id: 'u1', email: 'a@example.com', role: 'USER', createdAt: null },
    });
    renderLogin();

    await user.type(screen.getByLabelText(/email/i), 'a@example.com');
    await user.type(screen.getByLabelText(/password/i), 'Secret123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Dashboard')).toBeInTheDocument();
    expect(localStorage.getItem('finagent.accessToken')).toBe('tok');
  });

  it('shows the backend error message on failed login', async () => {
    const user = userEvent.setup();
    mockedLogin.mockRejectedValue(
      new api.ApiClientError(401, 'Invalid email or password', {
        status: 401,
        error: 'Unauthorized',
        message: 'Invalid email or password',
        path: '/api/v1/auth/login',
        fieldErrors: [],
      }),
    );
    renderLogin();

    await user.type(screen.getByLabelText(/email/i), 'a@example.com');
    await user.type(screen.getByLabelText(/password/i), 'Secret12345');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
  });

  it('registers a new account and navigates home', async () => {
    const user = userEvent.setup();
    mockedRegister.mockResolvedValue({
      tokenType: 'Bearer',
      accessToken: 'tok2',
      expiresIn: 3600,
      user: { id: 'u2', email: 'new@example.com', role: 'USER', createdAt: null },
    });
    render(
      <MemoryRouter initialEntries={['/register']}>
        <AuthProvider>
          <Routes>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/" element={<div>Dashboard</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/email/i), 'new@example.com');
    await user.type(screen.getByLabelText(/password/i), 'Secret123');
    await user.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText('Dashboard')).toBeInTheDocument();
    expect(mockedRegister).toHaveBeenCalledWith('new@example.com', 'Secret123');
  });
});

describe('RequireAuth', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
  });

  it('redirects unauthenticated visitors to login without rendering protected content', async () => {
    render(
      <MemoryRouter initialEntries={['/history']}>
        <AuthProvider>
          <Routes>
            <Route
              path="/history"
              element={
                <RequireAuth>
                  <div>Secret research</div>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<div>Login page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(screen.queryByText('Secret research')).not.toBeInTheDocument();
    expect(mockedMe).not.toHaveBeenCalled();
  });

  it('renders protected content for a stored valid session', async () => {
    localStorage.setItem(
      'finagent.accessToken',
      'valid-token',
    );
    mockedMe.mockResolvedValue({ id: 'u1', email: 'a@example.com', role: 'USER', createdAt: null });
    render(
      <MemoryRouter initialEntries={['/history']}>
        <AuthProvider>
          <Routes>
            <Route
              path="/history"
              element={
                <RequireAuth>
                  <div>Secret research</div>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<div>Login page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('Secret research')).toBeInTheDocument();
    await waitFor(() => expect(mockedMe).toHaveBeenCalled());
  });

  it('an expired session clears the token and bounces to login', async () => {
    localStorage.setItem('finagent.accessToken', 'expired-token');
    mockedMe.mockRejectedValue(
      new api.ApiClientError(401, 'Unauthorized', {
        status: 401,
        error: 'Unauthorized',
        message: 'Unauthorized',
        path: '/api/v1/auth/me',
        fieldErrors: [],
      }),
    );
    render(
      <MemoryRouter initialEntries={['/history']}>
        <AuthProvider>
          <Routes>
            <Route
              path="/history"
              element={
                <RequireAuth>
                  <div>Secret research</div>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<div>Login page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    // Phase 17: stale tokens are purged on load — never left for reuse.
    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(localStorage.getItem('finagent.accessToken')).toBeNull();
  });
});
