import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NewResearchPage } from './NewResearchPage';
import * as api from '../api/client';

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>();
  return { ...original, createResearch: vi.fn() };
});

const mockedCreate = vi.mocked(api.createResearch);

function renderForm() {
  render(
    <MemoryRouter initialEntries={['/research/new']}>
      <Routes>
        <Route path="/research/new" element={<NewResearchPage />} />
        <Route path="/research/:id" element={<div>Details page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('NewResearchPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('rejects a blank question', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: /submit research/i }));

    expect(await screen.findByText(/must not be blank/i)).toBeInTheDocument();
    expect(mockedCreate).not.toHaveBeenCalled();
  });

  it('rejects invalid tickers', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/research question/i), 'Analyze it');
    await user.type(screen.getByLabelText(/tickers/i), 'BAD!SYMBOL');
    await user.click(screen.getByRole('button', { name: /submit research/i }));

    expect(await screen.findByText(/is invalid/i)).toBeInTheDocument();
    expect(mockedCreate).not.toHaveBeenCalled();
  });

  it('rejects more than ten tickers', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/research question/i), 'Analyze all');
    await user.type(
      screen.getByLabelText(/tickers/i),
      'A1 A2 A3 A4 A5 A6 A7 A8 A9 A10 A11',
    );
    await user.click(screen.getByRole('button', { name: /submit research/i }));

    expect(await screen.findByText(/at most 10 tickers/i)).toBeInTheDocument();
  });

  it('submits valid input and navigates to the details page', async () => {
    const user = userEvent.setup();
    mockedCreate.mockResolvedValue({ researchId: 'job-1', status: 'PENDING', createdAt: '2026-09-04T10:00:00Z' });
    renderForm();

    await user.type(screen.getByLabelText(/research question/i), 'Analyze AAPL');
    await user.type(screen.getByLabelText(/tickers/i), 'aapl');
    await user.click(screen.getByRole('button', { name: /submit research/i }));

    await waitFor(() => expect(screen.getByText('Details page')).toBeInTheDocument());
    expect(mockedCreate).toHaveBeenCalledWith({ query: 'Analyze AAPL', tickers: ['AAPL'] });
  });

  it('shows backend errors without stack traces', async () => {
    const user = userEvent.setup();
    mockedCreate.mockRejectedValue(new api.ApiClientError(500, 'backend exploded'));
    renderForm();

    await user.type(screen.getByLabelText(/research question/i), 'Analyze AAPL');
    await user.click(screen.getByRole('button', { name: /submit research/i }));

    expect(await screen.findByText(/unexpected error/i)).toBeInTheDocument();
  });
});
