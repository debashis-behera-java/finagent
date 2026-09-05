import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ResearchDetailsPage } from './ResearchDetailsPage';
import * as api from '../api/client';
import { completedResearch, failedResearch } from '../test-fixtures';

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>();
  return {
    ...original,
    getResearch: vi.fn(),
    downloadResearchPdf: vi.fn(),
    saveBlob: vi.fn(),
  };
});

const mockedGet = vi.mocked(api.getResearch);

function renderDetails(id = 'job-1') {
  render(
    <MemoryRouter initialEntries={[`/research/${id}`]}>
      <Routes>
        <Route path="/research/:id" element={<ResearchDetailsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ResearchDetailsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });
  it('shows progress while pending and stops at completion', async () => {
    mockedGet
      .mockResolvedValueOnce({ ...completedResearch(), status: 'PENDING', result: null })
      .mockResolvedValueOnce({ ...completedResearch(), status: 'RUNNING', result: null })
      .mockResolvedValue(completedResearch());
    renderDetails();

    expect(await screen.findByText(/queued|running/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/executive summary/i)).toBeInTheDocument(), { timeout: 10000 });
    expect(mockedGet.mock.calls.length).toBeGreaterThanOrEqual(3);
  }, 15000);

  it('renders the completed result with all sections', async () => {
    mockedGet.mockResolvedValue(completedResearch());
    renderDetails();

    expect(await screen.findByText(/executive summary/i)).toBeInTheDocument();
    expect(screen.getByText('AAPL looks steady with moderate risk.')).toBeInTheDocument();
    // Values repeat across card + table views — assert presence, not uniqueness.
    expect(screen.getAllByText('150.25').length).toBeGreaterThan(0);
    expect(screen.getAllByText('MODERATE').length).toBeGreaterThan(0);
    expect(screen.getAllByText('POSITIVE').length).toBeGreaterThan(0);
    expect(screen.getByText(/lexicon-v1/)).toBeInTheDocument();
    expect(screen.getByText(/AAPL beats earnings/)).toBeInTheDocument();
    expect(screen.getByText(/quote unavailable for ZZZZ/)).toBeInTheDocument();
    expect(screen.getByText(/Educational research only/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /download pdf report/i })).toBeInTheDocument();
  });

  it('renders Unavailable for missing values and never NEUTRAL for unavailable sentiment', async () => {
    mockedGet.mockResolvedValue(completedResearch());
    renderDetails();

    await screen.findByText(/executive summary/i);
    expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(0);
    expect(screen.getByText(/unavailable \(NO_ARTICLES\)/i)).toBeInTheDocument();
    expect(screen.queryByText('NEUTRAL')).not.toBeInTheDocument();
  });

  it('renders failed research with code and message', async () => {
    mockedGet.mockResolvedValue(failedResearch());
    renderDetails();

    expect(await screen.findByText(/research failed \(NO_FACTS\)/i)).toBeInTheDocument();
    expect(screen.getByText('All data sources failed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /download pdf/i })).not.toBeInTheDocument();
  });

  it('shows a friendly 404 when the backend has no such research', async () => {
    mockedGet.mockRejectedValue(new api.ApiClientError(404, 'Research not found'));
    renderDetails();

    expect(await screen.findByText(/not found/i)).toBeInTheDocument();
  });

  it('downloads the PDF on button click and reports download errors', async () => {
    const user = userEvent.setup();
    mockedGet.mockResolvedValue(completedResearch());
    const mockedDownload = vi.mocked(api.downloadResearchPdf);
    const blob = new Blob(['%PDF'], { type: 'application/pdf' });
    mockedDownload.mockResolvedValue({ blob, filename: 'finagent-research-job.pdf' });
    renderDetails();

    await user.click(await screen.findByRole('button', { name: /download pdf report/i }));
    await waitFor(() => expect(api.saveBlob).toHaveBeenCalledWith(blob, 'finagent-research-job.pdf'));

    mockedDownload.mockRejectedValueOnce(new api.ApiClientError(409, 'Research is RUNNING'));
    await user.click(screen.getByRole('button', { name: /download pdf report/i }));
    expect(await screen.findByText(/not completed yet/i)).toBeInTheDocument();
  });
});
