import type { Research } from './types';

/** Shared API fixtures for component tests (mocked backend responses). */
export function completedResearch(): Research {
  return {
    researchId: '11111111-2222-3333-4444-555555555555',
    status: 'COMPLETED',
    createdAt: '2026-09-04T10:00:00Z',
    startedAt: '2026-09-04T10:00:05Z',
    completedAt: '2026-09-04T10:01:00Z',
    requestText: 'Analyze AAPL and ZZZZ',
    tickers: ['AAPL', 'ZZZZ'],
    result: {
      tickers: ['AAPL', 'ZZZZ'],
      executiveSummary: 'AAPL looks steady with moderate risk.',
      interpretation: 'Coverage reads constructive. Educational research, not advice.',
      metrics: {
        tickers: ['AAPL', 'ZZZZ'],
        guard: { passed: true, redactedCount: 0 },
        entries: [
          {
            symbol: 'AAPL',
            price: 150.25,
            change: 2.5,
            changePercent: 1.69,
            companyName: 'AAPL Corp.',
            sector: 'Technology',
            peRatio: 28.5,
            historyBars: 21,
            newsCount: 2,
            headlines: ['AAPL beats earnings expectations', 'AAPL raises guidance'],
            risk: { volatility: 0.21, maxDrawdown: 0.045, beta: 1.15, sharpe: null, score: 42.5, category: 'MODERATE' },
            sentiment: {
              status: 'available',
              label: 'POSITIVE',
              score: 0.6,
              confidence: 0.8,
              articleCount: 2,
              analyzedCount: 2,
              positiveCount: 2,
              neutralCount: 0,
              negativeCount: 0,
              methodology: 'lexicon-v1',
            },
            gaps: [],
          },
          {
            symbol: 'ZZZZ',
            price: null,
            companyName: 'ZZZZ Corp.',
            historyBars: 0,
            newsCount: 0,
            headlines: [],
            risk: null,
            sentiment: { status: 'unavailable', reason: 'NO_ARTICLES' },
            gaps: ['quote unavailable for ZZZZ'],
          },
        ],
      },
      newsSummary: 'AAPL (2 headlines)',
      disclaimer: 'Educational research only, not financial advice.',
      completedAt: '2026-09-04T10:01:00Z',
    },
    error: null,
  };
}

export function failedResearch(): Research {
  return {
    researchId: '99999999-2222-3333-4444-555555555555',
    status: 'FAILED',
    createdAt: '2026-09-04T10:00:00Z',
    requestText: 'Analyze FAIL',
    tickers: ['FAIL'],
    result: null,
    error: { code: 'NO_FACTS', message: 'All data sources failed' },
  };
}
