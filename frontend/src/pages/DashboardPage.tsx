import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { friendlyMessage, getResearchHistory } from '../api/client';
import type { Research } from '../types';
import { formatDateTime } from '../utils/format';
import { ErrorAlert, EmptyState } from '../components/Feedback';
import { LoadingSkeleton } from '../components/LoadingState';
import { StatusBadge } from '../components/StatusBadge';

/** Landing page: product intro, entry points, and recent research. No invented stats. */
export function DashboardPage() {
  const [recent, setRecent] = useState<Research[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getResearchHistory(0, 5)
      .then((history) => {
        if (!cancelled) {
          setRecent(history.content);
          setLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(friendlyMessage(err));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="space-y-6">
      <div className="rounded-lg bg-slate-900 p-6 text-white sm:p-8">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">FinAgent</h1>
        <p className="mt-2 max-w-2xl text-sm text-slate-300 sm:text-base">
          AI-powered financial research: submit a question, track deterministic market analysis,
          and download a professional PDF report. Educational research — not financial advice.
        </p>
        <Link
          to="/research/new"
          className="mt-4 inline-block rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
        >
          Start new research
        </Link>
      </div>

      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold">Recent research</h2>
          <Link to="/history" className="text-sm font-medium text-blue-700 hover:underline">
            View all
          </Link>
        </div>
        {loading && <LoadingSkeleton lines={3} />}
        {error && <ErrorAlert message={error} onRetry={() => window.location.reload()} />}
        {!loading && !error && recent.length === 0 && (
          <EmptyState title="No research yet" detail="Submit your first research request to get started." />
        )}
        {!loading && !error && recent.length > 0 && (
          <ul className="space-y-2">
            {recent.map((item) => (
              <li key={item.researchId} className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
                <Link to={`/research/${item.researchId}`} className="block rounded focus-visible:outline-2 focus-visible:outline-blue-600">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium">{item.requestText || item.researchId}</span>
                    <StatusBadge status={item.status} />
                  </div>
                  <p className="mt-1 text-xs text-slate-500">{formatDateTime(item.createdAt)}</p>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
