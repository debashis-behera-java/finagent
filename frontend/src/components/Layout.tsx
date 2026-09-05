import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

function navClass({ isActive }: { isActive: boolean }): string {
  return `rounded-md px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 ${
    isActive ? 'bg-blue-700 text-white' : 'text-slate-200 hover:bg-slate-700 hover:text-white'
  }`;
}

/** App shell: header nav + main outlet + footer. Responsive, keyboard-navigable. */
export function Layout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  return (
    <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2"
      >
        Skip to content
      </a>
      <header className="bg-slate-900 text-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-2 px-4 py-3 sm:px-6">
          <Link to="/" className="mr-2 flex items-center gap-2 rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-400">
            <span className="flex h-8 w-8 items-center justify-center rounded bg-blue-600 text-sm font-bold" aria-hidden="true">
              F
            </span>
            <span className="text-lg font-semibold tracking-tight">FinAgent</span>
          </Link>
          <nav aria-label="Primary" className="flex flex-wrap items-center gap-1">
            <NavLink to="/" end className={navClass}>
              Dashboard
            </NavLink>
            <NavLink to="/research/new" className={navClass}>
              New Research
            </NavLink>
            <NavLink to="/history" className={navClass}>
              History
            </NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-3">
            {user ? (
              <>
                <span className="hidden text-xs text-slate-300 sm:inline" title="Signed-in account">
                  {user.email}
                </span>
                <button
                  type="button"
                  onClick={() => {
                    signOut();
                    navigate('/login', { replace: true });
                  }}
                  className="rounded-md px-3 py-2 text-sm font-medium text-slate-200 hover:bg-slate-700 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-400"
                >
                  Sign out
                </button>
              </>
            ) : (
              <Link
                to="/login"
                className="rounded-md px-3 py-2 text-sm font-medium text-slate-200 hover:bg-slate-700 hover:text-white"
              >
                Sign in
              </Link>
            )}
          </div>
        </div>
      </header>
      <main id="main-content" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6">
        <Outlet />
      </main>
      <footer className="border-t border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl px-4 py-4 text-xs text-slate-500 sm:px-6">
          FinAgent provides educational investment research, not financial advice.
        </div>
      </footer>
    </div>
  );
}
