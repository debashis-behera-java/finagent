import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useResearchPolling } from './useResearchPolling';
import * as api from '../api/client';
import { completedResearch } from '../test-fixtures';

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>();
  return { ...original, getResearch: vi.fn() };
});

const mockedGet = vi.mocked(api.getResearch);

function Probe({ id, interval }: { id: string; interval: number }) {
  const { research, loading, error } = useResearchPolling(id, interval);
  return (
    <div>
      <div data-testid="status">{loading ? 'loading' : (research?.status ?? 'none')}</div>
      {error && <div data-testid="error">{error}</div>}
    </div>
  );
}

describe('useResearchPolling', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });
  it('polls through PENDING/RUNNING and stops at COMPLETED', async () => {
    mockedGet
      .mockResolvedValueOnce({ ...completedResearch(), status: 'PENDING', result: null })
      .mockResolvedValueOnce({ ...completedResearch(), status: 'RUNNING', result: null })
      .mockResolvedValue(completedResearch());
    render(<Probe id="job-1" interval={10} />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('COMPLETED'), { timeout: 5000 });
    const callsAfterSettled = mockedGet.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(mockedGet.mock.calls.length).toBe(callsAfterSettled);
  });

  it('stops polling on FAILED and reports backend errors', async () => {
    mockedGet.mockRejectedValueOnce(new api.ApiClientError(500, 'backend exploded'));
    render(<Probe id="missing" interval={10} />);

    expect(await screen.findByTestId('error')).toHaveTextContent(/unexpected error/i);
    expect(mockedGet).toHaveBeenCalledTimes(1);
  });
});
