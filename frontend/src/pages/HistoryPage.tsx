import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { friendlyMessage, getResearchHistory } from '../api/client';
import type { Research } from '../types';
import { formatDateTime } from '../utils/format';
import { EmptyState, ErrorAlert } from '../components/Feedback';
import { LoadingSkeleton } from '../components/LoadingState';
import { StatusBadge } from '../components/StatusBadge';
import { Pagination } from '../components/Controls';

const PAGE_SIZE = 10;

/** Paged research history, newest first, respecting backend pagination. */
export function HistoryPage() {
  const [page, setPage] = useState(0);
  const [items, setItems] = useState<Research[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (pageNumber: number) => {
    setLoading(true);
    setError(null);
    try {
      const history = await getResearchHistory(pageNumber, PAGE_SIZE);
      setItems(history.content);
      setTotalPages(history.totalPages);
      setTotalElements(history.totalElements);
      setPage(history.page);
    } catch (err) {
      setError(friendlyMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(0);
  }, [load]);

  return (
    <div>
      <h1 className="text-xl font-bold tracking-tight sm:text-2xl">Research history</h1>
      <p className="mt-1 text-sm text-slate-500">Newest first. Select a record to open its details.</p>
      <div className="mt-4">
        {loading && <LoadingSkeleton lines={5} />}
        {error && <ErrorAlert message={error} onRetry={() => void load(page)} />}
        {!loading && !error && items.length === 0 && (
          <EmptyState title="No research history" detail="Completed and running research will appear here." />
        )}
        {!loading && !error && items.length > 0 && (
          <ul className="space-y-2">
            {items.map((item) => (
              <li key={item.researchId} className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
                <Link to={`/research/${item.researchId}`} className="block rounded focus-visible:outline-2 focus-visible:outline-blue-600">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium">{item.requestText || item.researchId}</span>
                    <StatusBadge status={item.status} />
                  </div>
                  <p className="mt-1 text-xs text-slate-500">
                    {formatDateTime(item.createdAt)}
                    {item.completedAt ? ` · completed ${formatDateTime(item.completedAt)}` : ''}
                    {(item.tickers?.length ?? 0) > 0 ? ` · ${item.tickers!.join(', ')}` : ''}
                  </p>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {!loading && !error && (
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} onPage={(p) => void load(p)} />
        )}
      </div>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="py-10 text-center">
      <h1 className="text-xl font-bold">Page not found</h1>
      <p className="mt-1 text-sm text-slate-500">The page you are looking for does not exist.</p>
      <Link to="/" className="mt-4 inline-block text-sm font-medium text-blue-700 hover:underline">
        Back to dashboard
      </Link>
    </div>
  );
}
