import type { MetricsEntry } from '../types';
import { UNAVAILABLE, percent, signedNumber, valueOrUnavailable } from '../utils/format';
import { Section } from './Section';

/** Per-stock summary card. Renders backend values only — no calculations here. */
export function StockCard({ entry }: { entry: MetricsEntry }) {
  const risk = entry.risk;
  const sentiment = entry.sentiment;
  return (
    <article aria-label={`Stock ${entry.symbol}`} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-lg font-bold tracking-tight">{entry.symbol}</h3>
        <span className="text-sm text-slate-500">{valueOrUnavailable(entry.companyName)}</span>
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-4">
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Price</dt>
          <dd className="text-base font-semibold">{valueOrUnavailable(entry.price)}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Change</dt>
          <dd className="text-base font-semibold">
            {entry.change === null || entry.change === undefined ? UNAVAILABLE : `${signedNumber(entry.change)} (${percent(entry.changePercent)})`}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Risk</dt>
          <dd className="text-base font-semibold">
            {risk?.category ?? UNAVAILABLE}
            {risk?.score !== null && risk?.score !== undefined ? ` (${risk.score})` : ''}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Sentiment</dt>
          <dd className="text-base font-semibold">
            {sentiment && sentiment.status === 'available' ? (sentiment.label ?? UNAVAILABLE) : UNAVAILABLE}
          </dd>
        </div>
      </dl>
      {(entry.sector || entry.peRatio !== null) && (
        <p className="mt-2 text-xs text-slate-500">
          {[entry.sector, entry.peRatio !== null && entry.peRatio !== undefined ? `P/E ${entry.peRatio}` : null]
            .filter(Boolean)
            .join(' · ')}
        </p>
      )}
    </article>
  );
}

/** Cross-ticker market data table (responsive: horizontal scroll on small screens). */
export function MarketTable({ entries }: { entries: MetricsEntry[] }) {
  return (
    <Section title="Market Data">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[480px] border-collapse text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
              <th scope="col" className="py-2 pr-4 font-medium">Symbol</th>
              <th scope="col" className="py-2 pr-4 font-medium">Price</th>
              <th scope="col" className="py-2 pr-4 font-medium">Change</th>
              <th scope="col" className="py-2 font-medium">Change %</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.symbol} className="border-b border-slate-100 last:border-0">
                <th scope="row" className="py-2 pr-4 font-semibold">{entry.symbol}</th>
                <td className="py-2 pr-4">{valueOrUnavailable(entry.price)}</td>
                <td className="py-2 pr-4">{entry.change === null || entry.change === undefined ? UNAVAILABLE : signedNumber(entry.change)}</td>
                <td className="py-2">{percent(entry.changePercent)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

/** Cross-ticker risk table. Missing metrics show "Unavailable" individually. */
export function RiskTable({ entries }: { entries: MetricsEntry[] }) {
  const rows = entries.filter((e) => e.risk);
  if (rows.length === 0) return null;
  return (
    <Section title="Risk Analysis">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
              <th scope="col" className="py-2 pr-4 font-medium">Symbol</th>
              <th scope="col" className="py-2 pr-4 font-medium">Risk Score</th>
              <th scope="col" className="py-2 pr-4 font-medium">Risk Level</th>
              <th scope="col" className="py-2 pr-4 font-medium">Volatility</th>
              <th scope="col" className="py-2 font-medium">Max Drawdown</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((entry) => (
              <tr key={entry.symbol} className="border-b border-slate-100 last:border-0">
                <th scope="row" className="py-2 pr-4 font-semibold">{entry.symbol}</th>
                <td className="py-2 pr-4">{valueOrUnavailable(entry.risk?.score)}</td>
                <td className="py-2 pr-4">{valueOrUnavailable(entry.risk?.category)}</td>
                <td className="py-2 pr-4">{valueOrUnavailable(entry.risk?.volatility)}</td>
                <td className="py-2">{valueOrUnavailable(entry.risk?.maxDrawdown)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.some((e) => e.risk?.beta !== null || e.risk?.sharpe !== null) && (
        <dl className="mt-3 border-t border-slate-100 pt-2">
          {rows.map((entry) => (
            <div key={entry.symbol}>
              {(entry.risk?.beta !== null && entry.risk?.beta !== undefined) && (
                <div className="flex gap-3 py-0.5 text-sm"><dt className="w-40 text-slate-500">{entry.symbol} beta</dt><dd>{entry.risk?.beta}</dd></div>
              )}
              {(entry.risk?.sharpe !== null && entry.risk?.sharpe !== undefined) && (
                <div className="flex gap-3 py-0.5 text-sm"><dt className="w-40 text-slate-500">{entry.symbol} Sharpe</dt><dd>{entry.risk?.sharpe}</dd></div>
              )}
            </div>
          ))}
        </dl>
      )}
    </Section>
  );
}
