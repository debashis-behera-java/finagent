import { useEffect, useRef, useState } from 'react';
import { friendlyMessage, getResearch } from '../api/client';
import type { Research } from '../types';

const POLL_INTERVAL_MS = 2500;

export interface PollingState {
  research: Research | null;
  loading: boolean;
  error: string | null;
}

/**
 * Polls GET /api/v1/research/{id} every ~2.5s while the job is PENDING/RUNNING
 * and stops immediately on COMPLETED/FAILED (or unmount). Exactly one loop per
 * hook instance — no overlapping requests (waits for each fetch to settle).
 */
export function useResearchPolling(id: string | undefined, intervalMs: number = POLL_INTERVAL_MS): PollingState {
  const [research, setResearch] = useState<Research | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelled = useRef(false);

  useEffect(() => {
    cancelled.current = false;
    setResearch(null);
    setError(null);
    setLoading(true);
    if (!id) {
      setLoading(false);
      setError('No research ID provided.');
      return undefined;
    }

    let settled = true;
    const poll = async () => {
      if (cancelled.current) return;
      if (!settled) return;
      settled = false;
      try {
        const current = await getResearch(id);
        if (cancelled.current) return;
        setResearch(current);
        setError(null);
        setLoading(false);
        if (current.status === 'PENDING' || current.status === 'RUNNING') {
          timer.current = setTimeout(poll, intervalMs);
        }
      } catch (err) {
        if (cancelled.current) return;
        setError(friendlyMessage(err));
        setLoading(false);
      } finally {
        settled = true;
      }
    };

    void poll();
    return () => {
      cancelled.current = true;
      if (timer.current) clearTimeout(timer.current);
    };
  }, [id, intervalMs]);

  return { research, loading, error };
}
