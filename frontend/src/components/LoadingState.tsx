export function LoadingSpinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 py-6" role="status" aria-live="polite">
      <span
        aria-hidden="true"
        className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-slate-300 border-t-blue-600"
      />
      <span className="text-sm text-slate-600">{label}</span>
    </div>
  );
}

export function LoadingSkeleton({ lines = 4 }: { lines?: number }) {
  return (
    <div className="space-y-2 py-4" role="status" aria-label="Loading content">
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="h-4 animate-pulse rounded bg-slate-200" style={{ width: `${95 - i * 12}%` }} />
      ))}
    </div>
  );
}
