import { useParams } from 'react-router-dom';
import { useResearchPolling } from '../hooks/useResearchPolling';
import { formatDateTime, shortId } from '../utils/format';
import { ErrorAlert } from '../components/Feedback';
import { LoadingSpinner } from '../components/LoadingState';
import { StatusBadge } from '../components/StatusBadge';
import { Field, Section } from '../components/Section';
import { PdfDownloadButton } from '../components/Controls';
import { MarketTable, RiskTable, StockCard } from '../components/Stocks';
import { GapsList, NewsList, SentimentPanel } from '../components/News';

/** Research detail: polling progress → completed result / failed error. */
export function ResearchDetailsPage() {
  const { id } = useParams<{ id: string }>();
  const { research, loading, error } = useResearchPolling(id);

  if (loading && !research) {
    return (
      <div>
        <h1 className="text-xl font-bold">Research {id ? shortId(id) : ''}</h1>
        <LoadingSpinner label="Loading research…" />
      </div>
    );
  }
  if (error && !research) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-bold">Research</h1>
        <ErrorAlert message={error} onRetry={() => window.location.reload()} />
      </div>
    );
  }
  if (!research) return null;

  const entries = research.result?.metrics?.entries ?? [];
  const inProgress = research.status === 'PENDING' || research.status === 'RUNNING';

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="truncate text-xl font-bold tracking-tight">Research {shortId(research.researchId)}</h1>
          <p className="mt-1 break-words text-sm text-slate-600">
            {research.requestText || 'No request text recorded.'}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Created {formatDateTime(research.createdAt)}
            {research.completedAt ? ` · Completed ${formatDateTime(research.completedAt)}` : ''}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          <StatusBadge status={research.status} />
          {research.status === 'COMPLETED' && <PdfDownloadButton researchId={research.researchId} />}
        </div>
      </div>

      {inProgress && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 p-4" role="status" aria-live="polite">
          <LoadingSpinner label={research.status === 'PENDING' ? 'Queued — starting analysis…' : 'Analysis running… this usually takes under a minute.'} />
          <p className="text-xs text-slate-500">Status refreshes automatically every few seconds.</p>
        </div>
      )}

      {research.status === 'FAILED' && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4" role="alert">
          <p className="text-sm font-semibold text-red-800">Research failed{research.error?.code ? ` (${research.error.code})` : ''}</p>
          <p className="mt-1 text-sm text-red-700">{research.error?.message ?? 'The analysis could not be completed.'}</p>
        </div>
      )}

      {research.status === 'COMPLETED' && research.result && (
        <>
          {research.result.executiveSummary && (
            <Section title="Executive Summary">
              <p className="whitespace-pre-wrap text-sm leading-relaxed">{research.result.executiveSummary}</p>
            </Section>
          )}

          {entries.length > 0 && (
            <>
              <div className="grid gap-4 md:grid-cols-2">
                {entries.map((entry) => (
                  <StockCard key={entry.symbol} entry={entry} />
                ))}
              </div>
              <MarketTable entries={entries} />
              <RiskTable entries={entries} />
              <SentimentPanel entries={entries} />
              <NewsList entries={entries} />
            </>
          )}

          {research.result.interpretation && (
            <Section title="AI Interpretation">
              <p className="whitespace-pre-wrap text-sm leading-relaxed">{research.result.interpretation}</p>
            </Section>
          )}

          <GapsList entries={entries} guardRedactions={research.result.metrics?.guard?.redactedCount} />

          {research.result.newsSummary && (
            <Section title="News Summary">
              <p className="whitespace-pre-wrap text-sm leading-relaxed">{research.result.newsSummary}</p>
            </Section>
          )}

          <Section title="Run Details">
            <dl>
              <Field label="Research ID" value={<span className="break-all font-mono text-xs">{research.researchId}</span>} />
              <Field label="Tickers" value={research.result.tickers?.join(', ') || '—'} />
              <Field label="Started" value={formatDateTime(research.startedAt)} />
              <Field label="Completed" value={formatDateTime(research.completedAt)} />
            </dl>
          </Section>

          {research.result.disclaimer && (
            <div className="rounded-lg border border-slate-300 bg-slate-100 p-4">
              <h2 className="text-sm font-semibold">Disclaimer</h2>
              <p className="mt-1 text-sm text-slate-700">{research.result.disclaimer}</p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
