import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiClientError, createResearch, friendlyMessage } from '../api/client';

const MAX_QUERY = 2000;
const TICKER_PATTERN = /^[A-Za-z0-9.\-]{1,12}$/;

/** Research submission form with client-side validation mirroring backend rules. */
export function NewResearchPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [tickers, setTickers] = useState('');
  const [errors, setErrors] = useState<string[]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const validate = (q: string, t: string): string[] => {
    const problems: string[] = [];
    if (q.trim() === '') problems.push('Research question must not be blank.');
    if (q.length > MAX_QUERY) problems.push(`Research question must be at most ${MAX_QUERY} characters.`);
    const list = t.split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);
    if (list.length > 10) problems.push('At most 10 tickers per request.');
    for (const ticker of list) {
      if (!TICKER_PATTERN.test(ticker)) {
        problems.push(`Ticker "${ticker}" is invalid — use 1–12 letters, digits, "." or "-".`);
        break;
      }
    }
    return problems;
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const problems = validate(query, tickers);
    setErrors(problems);
    setSubmitError(null);
    if (problems.length > 0) return;
    setSubmitting(true);
    try {
      const list = tickers.split(/[,\s]+/).map((s) => s.trim().toUpperCase()).filter(Boolean);
      const accepted = await createResearch({ query: query.trim(), tickers: list });
      await navigate(`/research/${accepted.researchId}`);
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

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-xl font-bold tracking-tight sm:text-2xl">New research</h1>
      <p className="mt-1 text-sm text-slate-500">
        Ask a question, optionally with tickers. Example: “Analyze TCS, INFY and RELIANCE for a
        moderate-risk investor with a 3-year horizon.”
      </p>
      <form onSubmit={(e) => void submit(e)} className="mt-4 space-y-4" noValidate>
        <div>
          <label htmlFor="query" className="mb-1 block text-sm font-medium">
            Research question
          </label>
          <textarea
            id="query"
            rows={5}
            value={query}
            maxLength={MAX_QUERY + 100}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="What should FinAgent research?"
            aria-describedby="query-help"
            aria-invalid={errors.length > 0}
            className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-600 focus:outline-none focus:ring-1 focus:ring-blue-600"
          />
          <p id="query-help" className="mt-1 text-xs text-slate-500">
            {query.length}/{MAX_QUERY} characters. Mention $TICKER symbols or list them below.
          </p>
        </div>
        <div>
          <label htmlFor="tickers" className="mb-1 block text-sm font-medium">
            Tickers <span className="font-normal text-slate-500">(optional, comma or space separated, max 10)</span>
          </label>
          <input
            id="tickers"
            type="text"
            value={tickers}
            onChange={(e) => setTickers(e.target.value)}
            placeholder="AAPL, MSFT"
            autoComplete="off"
            spellCheck={false}
            className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm uppercase shadow-sm focus:border-blue-600 focus:outline-none focus:ring-1 focus:ring-blue-600"
          />
        </div>
        {errors.length > 0 && (
          <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3" role="alert" aria-live="assertive">
            <ul className="list-disc space-y-0.5 pl-5 text-sm text-red-700">
              {errors.map((problem, i) => (
                <li key={i}>{problem}</li>
              ))}
            </ul>
          </div>
        )}
        {submitError && (
          <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3" role="alert">
            <p className="text-sm text-red-700">{submitError}</p>
          </div>
        )}
        <button
          type="submit"
          disabled={submitting}
          className="rounded-md bg-blue-700 px-5 py-2.5 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60 hover:bg-blue-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        >
          {submitting ? 'Submitting…' : 'Submit research'}
        </button>
      </form>
    </div>
  );
}
