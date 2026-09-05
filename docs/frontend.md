# FINAGENT — React Research Dashboard (Phase 11)

## 1. Frontend architecture

Vite 7 + React 18 + TypeScript (strict) + Tailwind CSS v4 + React Router 6.
No state-management library, no data-fetching library — `useState`/`useEffect`
plus one polling hook cover everything. Business logic stays in the backend;
React renders API responses only (numbers are displayed, never computed —
missing values show "Unavailable", never faked).

```
frontend/src/
  api/client.ts        # sole HTTP layer: createResearch/getResearch/
                       # getResearchHistory/downloadResearchPdf + ApiClientError
  types.ts             # mirrors of backend DTOs (responses only)
  hooks/useResearchPolling.ts   # 2.5s poll while PENDING/RUNNING, stops on terminal
  utils/format.ts      # valueOrUnavailable, signedNumber, percent, dates
  components/          # Layout, StatusBadge, LoadingState, Feedback, Section,
                       # Stocks (StockCard/MarketTable/RiskTable),
                       # News (SentimentPanel/NewsList/GapsList), Controls
  pages/               # DashboardPage, NewResearchPage, ResearchDetailsPage,
                       # HistoryPage (+ NotFoundPage)
  test-fixtures.ts     # mocked API payloads for component tests
```

## 2. Routes

| Route | Page |
|---|---|
| `/` | Dashboard (intro, new-research CTA, 5 most recent) |
| `/research/new` | Submission form (validation mirrors backend: query ≤2000, ≤10 tickers, symbol pattern) → navigates to `/research/{id}` on 202 |
| `/research/:id` | Progress UI while pending/running → full result (summary, cards, tables, sentiment, news, interpretation, gaps, disclaimer) + PDF download |
| `/history` | Paged history (newest first, backend pagination, click-through) |

## 3. API client

All fetch calls live in `api/client.ts` with typed methods, 15s timeouts
(30s for PDFs), and `friendlyMessage()` mapping (0 → backend-unreachable,
400/404/409/500 → human text from the safe `ApiError` body). No stack traces
ever reach the UI.

## 4. Polling

`useResearchPolling(id)`: fetches immediately, then re-fetches every ~2.5s
only while status is PENDING/RUNNING; stops on COMPLETED/FAILED, on error, and
on unmount. One sequential loop per hook instance (no overlapping requests,
no duplicate loops for one ID). No WebSockets (out of scope).

## 5. Components

Reusable, responsive (stacked → `sm:` grids, scrollable tables), accessible
(skip link, labels, focus rings, `role="status/alert"`, text+color status and
risk/sentiment badges — UNAVAILABLE is never rendered as NEUTRAL). Missing
backend data renders "Unavailable" with reasons where provided.

## 6. PDF download

`downloadResearchPdf(id)` fetches the backend-generated blob and saves it as
`finagent-research-{id}.pdf` (server filename respected); 409/404/500 map to
friendly messages. React never generates PDFs.

## 7. Environment configuration

`frontend/.env.example` documents the only public setting:
`VITE_API_BASE_URL=http://localhost:8080`. No keys of any kind in frontend
config. Backend CORS (`FINAGENT_CORS_ALLOWED_ORIGINS`, default
`http://localhost:5173`, GET/POST on `/api/**`, no credentials) covers
separately-hosted builds; the Vite dev server additionally proxies `/api` to
the backend.

## 8. Development / build commands

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 (proxies /api to :8080)
npm test         # vitest run (jsdom, mocked fetch — no backend needed)
npm run build    # tsc --noEmit + vite build → dist/
```

Backend (unchanged contract, one minimal addition — `WebConfig` CORS):
```bash
cd backend
mvn clean verify
```

## 9. Testing

42 Vitest + Testing Library tests, all with mocked fetch: form validation
(blank/invalid/>10 tickers), successful submit + navigation, backend error
mapping, polling through PENDING→RUNNING→COMPLETED and stop-on-terminal,
completed rendering (all sections, Unavailable handling, UNAVAILABLE≠NEUTRAL),
failed rendering, 404, PDF download success + 409 error, history list/paging/
empty/error-retry, API client shapes, format helpers.

## 10. Limitations

- No auth — history is global (same as backend Phase 8).
- No charts; tables + cards only (chart lib intentionally omitted).
- Polling only (no push); queued jobs show PENDING until the backend starts them.
- `dev` backend profile has no research endpoints, so the dashboard needs the
  `postgres` (or `test`) backend profile for live data.
