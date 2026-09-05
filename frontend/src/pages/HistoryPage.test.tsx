import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HistoryPage } from './HistoryPage';
import * as api from '../api/client';
import { completedResearch, failedResearch } from '../test-fixtures';

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>();
  return { ...original, getResearchHistory: vi.fn() };
});

const mockedHistory = vi.mocked(api.getResearchHistory);

function renderHistory() {
  render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/research/:id" element={<div>Details page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('HistoryPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });
  it('lists research newest first with statuses', async () => {
    mockedHistory.mockResolvedValue({
      content: [failedResearch(), completedResearch()],
      page: 0,
      size: 10,
      totalElements: 2,
      totalPages: 1,
    });
    renderHistory();

    expect(await screen.findByText('Analyze FAIL')).toBeInTheDocument();
    expect(screen.getByText('Analyze AAPL and ZZZZ')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
  });

  it('opens details when a record is clicked', async () => {
    const user = userEvent.setup();
    mockedHistory.mockResolvedValue({
      content: [completedResearch()],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    renderHistory();

    await user.click(await screen.findByText('Analyze AAPL and ZZZZ'));
    expect(await screen.findByText('Details page')).toBeInTheDocument();
  });

  it('pages through history', async () => {
    const user = userEvent.setup();
    mockedHistory
      .mockResolvedValueOnce({
        content: [completedResearch()],
        page: 0,
        size: 1,
        totalElements: 2,
        totalPages: 2,
      })
      .mockResolvedValueOnce({
        content: [failedResearch()],
        page: 1,
        size: 1,
        totalElements: 2,
        totalPages: 2,
      });
    renderHistory();

    expect(await screen.findByText('Analyze AAPL and ZZZZ')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /next/i }));
    await waitFor(() => expect(screen.getByText('Analyze FAIL')).toBeInTheDocument());
    expect(mockedHistory).toHaveBeenLastCalledWith(1, 10);
  });

  it('shows an empty state when there is no history', async () => {
    mockedHistory.mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    renderHistory();

    expect(await screen.findByText(/no research history/i)).toBeInTheDocument();
  });

  it('shows backend errors with retry', async () => {
    const user = userEvent.setup();
    mockedHistory.mockRejectedValueOnce(new api.ApiClientError(0, 'down')).mockResolvedValueOnce({
      content: [completedResearch()],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    renderHistory();

    expect(await screen.findByText(/cannot reach/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /try again/i }));
    expect(await screen.findByText('Analyze AAPL and ZZZZ')).toBeInTheDocument();
  });
});
