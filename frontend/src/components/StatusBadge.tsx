import type { ResearchStatus } from '../types';

const styles: Record<ResearchStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-900 ring-amber-600/20',
  RUNNING: 'bg-blue-100 text-blue-900 ring-blue-600/20',
  COMPLETED: 'bg-emerald-100 text-emerald-900 ring-emerald-600/20',
  FAILED: 'bg-red-100 text-red-900 ring-red-600/20',
};

/** Status pill with text label (never color-alone) for accessibility. */
export function StatusBadge({ status }: { status: ResearchStatus }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset ${styles[status] ?? 'bg-slate-100 text-slate-800'}`}
      aria-label={`Research status: ${status}`}
    >
      <span aria-hidden="true" className="inline-block h-1.5 w-1.5 rounded-full bg-current" />
      {status}
    </span>
  );
}
