import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClientError,
  createResearch,
  downloadResearchPdf,
  friendlyMessage,
  getResearch,
  getResearchHistory,
} from './client';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('submits research and returns the accepted job', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(202, { researchId: 'abc', status: 'PENDING', createdAt: '2026-09-04T10:00:00Z' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const accepted = await createResearch({ query: 'Analyze AAPL', tickers: ['AAPL'] });

    expect(accepted.researchId).toBe('abc');
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    // No VITE_API_BASE_URL in test env → same-origin relative URL (nginx proxy).
    expect(url).toBe('/api/v1/research');
    expect(JSON.parse(init.body as string)).toEqual({ query: 'Analyze AAPL', tickers: ['AAPL'] });
  });

  it('surfaces validation errors with field details', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(400, {
          status: 400,
          error: 'Validation Failed',
          message: 'Request validation failed',
          fieldErrors: [{ field: 'query', message: 'must not be blank' }],
        }),
      ),
    );

    const failure = await createResearch({ query: '  ' }).catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiClientError);
    expect((failure as ApiClientError).status).toBe(400);
    expect((failure as ApiClientError).body?.fieldErrors?.[0].field).toBe('query');
  });

  it('fetches single research and paged history', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { researchId: 'abc', status: 'COMPLETED' }))
      .mockResolvedValueOnce(
        jsonResponse(200, { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
      );
    vi.stubGlobal('fetch', fetchMock);

    expect((await getResearch('abc')).status).toBe('COMPLETED');
    expect((await getResearchHistory(1, 10)).page).toBe(0);
    expect(fetchMock.mock.calls[1]?.[0]).toContain('page=1&size=10');
  });

  it('maps failures to human messages without stack traces', () => {
    expect(friendlyMessage(new ApiClientError(0, 'x'))).toContain('Cannot reach');
    expect(friendlyMessage(new ApiClientError(404, 'x'))).toContain('not found');
    expect(friendlyMessage(new ApiClientError(409, 'x'))).toContain('not completed yet');
    expect(friendlyMessage(new ApiClientError(500, 'x'))).toContain('unexpected error');
    expect(friendlyMessage(new ApiClientError(400, 'm', { message: 'query must not be blank' }))).toContain(
      'query must not be blank',
    );
    expect(friendlyMessage(new Error('boom'))).toBe('boom');
  });

  it('downloads the PDF blob with the server filename', async () => {
    const bytes = new Uint8Array([0x25, 0x50, 0x44, 0x46]);
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(bytes, {
        status: 200,
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="finagent-research-abc.pdf"',
        },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const { blob, filename } = await downloadResearchPdf('abc');

    expect(filename).toBe('finagent-research-abc.pdf');
    expect(blob.type).toBe('application/pdf');
    expect(await blob.arrayBuffer().then((b) => b.byteLength)).toBe(4);
  });

  it('reports a friendly message when the PDF is not ready', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(409, { status: 409, message: 'Research is RUNNING' })),
    );

    const failure = await downloadResearchPdf('abc').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiClientError);
    expect(friendlyMessage(failure)).toContain('RUNNING');
  });

  it('treats network failure as unreachable backend', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    );

    const failure = await getResearch('abc').catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiClientError);
    expect((failure as ApiClientError).status).toBe(0);
  });
});
