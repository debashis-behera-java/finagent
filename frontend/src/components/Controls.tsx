import { useState } from 'react';
import { downloadResearchPdf, friendlyMessage, saveBlob } from '../api/client';

export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Research history pages" className="mt-4 flex items-center justify-between gap-2 text-sm">
      <p className="text-slate-500">
        Page {page + 1} of {totalPages} · {totalElements} total
      </p>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 font-medium disabled:cursor-not-allowed disabled:opacity-40 hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 font-medium disabled:cursor-not-allowed disabled:opacity-40 hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        >
          Next
        </button>
      </div>
    </nav>
  );
}

/** PDF download button with loading/error states. */
export function PdfDownloadButton({ researchId }: { researchId: string }) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const download = async () => {
    setDownloading(true);
    setError(null);
    try {
      const { blob, filename } = await downloadResearchPdf(researchId);
      saveBlob(blob, filename);
    } catch (err) {
      setError(friendlyMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div>
      <button
        type="button"
        onClick={() => void download()}
        disabled={downloading}
        className="rounded-md bg-blue-700 px-4 py-2 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60 hover:bg-blue-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
      >
        {downloading ? 'Preparing PDF…' : 'Download PDF Report'}
      </button>
      {error && (
        <p className="mt-2 text-sm text-red-700" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
