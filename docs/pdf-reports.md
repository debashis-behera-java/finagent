# FINAGENT — PDF Research Reports (Phase 10)

## 1. PDF architecture

```
GET /api/v1/research/{id}/report.pdf
        │  ResearchController (thin: headers, status codes)
        ▼
ResearchReportService (presentation only)
        │  COMPLETED check → ResearchStatusDto → ResearchReportData snapshot
        ▼
PdfReportGenerator (@Component, stateless singleton)
        │  Apache PDFBox 3.0.3 → byte[] (never written to disk)
        ▼
HTTP 200 application/pdf + Content-Disposition attachment
```

The generator performs **no** research, AI, MCP, or network calls — it renders
the already-persisted result. Raw `BigDecimal`/`Double` figures travel as plain
strings from the metrics JSON so nothing is re-rounded or recomputed.

## 2. PDFBox version

`org.apache.pdfbox:pdfbox:3.0.3` (pinned in `backend/pom.xml`). Verified 3.x API
surface before use: `PDType1Font` is constructed via
`new PDType1Font(Standard14Fonts.FontName.HELVETICA*)` (the 2.x static constants
are gone), and tests parse back with `Loader.loadPDF(byte[])` +
`PDFTextStripper`. Only standard-14 fonts are used — no embedding, no font
files, no network.

## 3. API endpoint

| Case | Response |
|---|---|
| Unknown id | `404` ApiError |
| `PENDING` / `RUNNING` / `FAILED` | `409` ApiError (`ReportNotReadyException`, safe message) |
| `COMPLETED` | `200 application/pdf`, `Content-Disposition: attachment; filename="finagent-research-{id}.pdf"`, `Content-Length` |
| Render failure | `500` fixed message (`ReportGenerationException`, cause logged with id) |

The filename derives from the path UUID only — no user-controlled paths, no
disk writes.

## 4. Report structure

Cover (brand, report title, research ID, generated/completed stamps, stocks,
quoted request) → Executive Summary → Stock Analysis (cross-ticker Market Data
table; per-ticker Fundamentals; cross-ticker Risk table + beta/Sharpe lines) →
News & Sentiment (article totals, sentiment table + per-ticker label lines,
methodology, overview, capped headlines) → AI Interpretation → Data Gaps &
Limitations (per-ticker gaps, guard-redaction count, baseline caveats) →
mandatory Disclaimer. Sections render **only** when they have data.

## 5. Multi-page handling

Single-pass flow layout (`Canvas`): cursor tracking against a bottom margin,
automatic page breaks for paragraphs, labeled lines, bullets, and individual
table rows (headers repeat on continuation pages). Every page gets the
`FINAGENT - Financial Research Report` header and a `Research ID … | Page N`
footer. A long interpretation provably spans pages (parse-back test asserts
`Page 2` and first/last findings).

## 6. Missing-data handling

`null`/blank values render as the word **"Unavailable"** — never 0, never
fabricated, never bare N/A. Sections without any data are omitted (e.g. no
risk table when no ticker has risk output). Unavailable sentiment shows its
reason (`NO_ARTICLES`, `PROVIDER_ERROR`, …), distinct from "not analyzed".

## 7. Security

No secrets flow into the renderer (input is the persisted DTO); long-text
sanitization maps exotic Unicode to WinAnsi-safe equivalents (Latin-1 kept,
em-dashes/quotes/rupee mapped, the rest `?`) so `showText` can never throw on
unmappable glyphs; errors expose no paths, traces, or internals.

## 8. Testing

- `PdfReportGeneratorTest` (no Spring): full-section parse-back, multi-stock,
  missing-data, unavailable-sentiment, multi-page, special-characters,
  no-secrets, null-rejection, sanitizer cases.
- `ResearchReportServiceTest` (mocked orchestrator): gating per status,
  404 passthrough, parse-back content, failure mapping.
- `ResearchReportControllerTest` (MockMvc, H2, mocked agent): 200 shape
  (content-type, disposition, `%PDF` magic, parsed content), 404, 409 × 3
  (persisted rows, no async racing), multi-stock, multi-page.
- Assertions read **parsed text and page counts**, never byte equality (PDF
  metadata varies); wrapped phrases are whitespace-normalized before matching.

## 9. Limitations

- Standard-14 fonts only: limited glyph coverage (sanitizer) and no kerning/
  advanced typography; charts/price graphs are out of scope.
- Reports render on demand — no caching, no background workers, no storage.
- No diversification section: per-run diversification output is not persisted
  (only emitted when such data exists — it currently does not).
- Headlines capped at 8 per ticker with an "…and N more" line.
