import type { MetricsEntry } from '../types';
import { valueOrUnavailable } from '../utils/format';
import { Section } from './Section';

/** Sentiment display. UNAVAILABLE (with reason) is never shown as NEUTRAL. */
export function SentimentPanel({ entries }: { entries: MetricsEntry[] }) {
  const withSentiment = entries.filter((e) => e.sentiment && e.sentiment.status !== 'not-analyzed');
  if (withSentiment.length === 0) return null;
  return (
    <Section title="News Sentiment">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
              <th scope="col" className="py-2 pr-4 font-medium">Symbol</th>
              <th scope="col" className="py-2 pr-4 font-medium">Articles</th>
              <th scope="col" className="py-2 pr-4 font-medium">Positive</th>
              <th scope="col" className="py-2 pr-4 font-medium">Neutral</th>
              <th scope="col" className="py-2 pr-4 font-medium">Negative</th>
              <th scope="col" className="py-2 font-medium">Score</th>
            </tr>
          </thead>
          <tbody>
            {withSentiment.map((entry) => {
              const s = entry.sentiment!;
              return (
                <tr key={entry.symbol} className="border-b border-slate-100 last:border-0">
                  <th scope="row" className="py-2 pr-4 font-semibold">{entry.symbol}</th>
                  {s.status === 'available' ? (
                    <>
                      <td className="py-2 pr-4">{s.analyzedCount ?? 0}</td>
                      <td className="py-2 pr-4">{s.positiveCount ?? 0}</td>
                      <td className="py-2 pr-4">{s.neutralCount ?? 0}</td>
                      <td className="py-2 pr-4">{s.negativeCount ?? 0}</td>
                      <td className="py-2">{valueOrUnavailable(s.score)} ({s.label})</td>
                    </>
                  ) : (
                    <td colSpan={5} className="py-2 text-slate-500">
                      Unavailable{s.reason ? ` (${s.reason})` : ''}
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {withSentiment.some((e) => e.sentiment?.methodology) && (
        <p className="mt-2 text-xs text-slate-500">
          Methodology: {withSentiment.find((e) => e.sentiment?.methodology)?.sentiment?.methodology}
        </p>
      )}
    </Section>
  );
}

/** Headline list. Headlines render as plain text only (no links) — the backend
 *  supplies headline strings without URLs, so there is no link/tabnabbing surface. */
export function NewsList({ entries }: { entries: MetricsEntry[] }) {
  const withNews = entries.filter((e) => (e.headlines?.length ?? 0) > 0);
  if (withNews.length === 0) {
    return (
      <Section title="News Headlines">
        <p className="text-sm text-slate-500">No news headlines are available for this research.</p>
      </Section>
    );
  }
  return (
    <Section title="News Headlines">
      <div className="space-y-4">
        {withNews.map((entry) => (
          <div key={entry.symbol}>
            <h3 className="text-sm font-semibold">{entry.symbol}</h3>
            <ul className="mt-1 list-disc space-y-1 pl-5 text-sm text-slate-700">
              {entry.headlines!.slice(0, 8).map((headline, i) => (
                <li key={i}>{headline}</li>
              ))}
            </ul>
            {(entry.headlines?.length ?? 0) > 8 && (
              <p className="mt-1 text-xs text-slate-500">…and {(entry.headlines?.length ?? 0) - 8} more (see PDF report).</p>
            )}
          </div>
        ))}
      </div>
    </Section>
  );
}

/** Data gaps, guard redactions, and limitations. */
export function GapsList({ entries, guardRedactions }: { entries: MetricsEntry[]; guardRedactions?: number }) {
  const gaps = entries.flatMap((e) => (e.gaps ?? []).map((gap) => `${e.symbol}: ${gap}`));
  if (gaps.length === 0 && !guardRedactions) return null;
  return (
    <Section title="Data Gaps & Limitations">
      <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
        {gaps.map((gap, i) => (
          <li key={i}>{gap}</li>
        ))}
        {!!guardRedactions && (
          <li>Safety guard redacted {guardRedactions} ungrounded figure(s) from the AI interpretation.</li>
        )}
        <li>Metrics are computed deterministically from retrieved market data.</li>
        <li>News sentiment is a keyword baseline, not professional financial analysis.</li>
      </ul>
    </Section>
  );
}
