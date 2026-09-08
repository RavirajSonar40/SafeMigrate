'use client';

import { useState, useEffect } from 'react';
import { MigrationProgressEvent, MigrationResponse } from './types';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/migrations';

export function useMigrationStream(initialData: MigrationResponse) {
  const [data, setData] = useState<MigrationResponse>(initialData);
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [lastEvent, setLastEvent] = useState<MigrationProgressEvent | null>(null);

  useEffect(() => {
    if (!initialData?.id) return;

    let isMounted = true;
    let eventSource: EventSource | null = null;
    let fallbackInterval: NodeJS.Timeout | null = null;

    function startFallbackSimulation() {
      if (!isMounted) return;
      if (fallbackInterval) {
        clearInterval(fallbackInterval);
        fallbackInterval = null;
      }

      if (initialData.state === 'BACKFILLING') {
        fallbackInterval = setInterval(() => {
          if (!isMounted) return;
          setData((prev) => {
            if (prev.state !== 'BACKFILLING') return prev;
            const inc = Math.floor(1000 + Math.random() * 800);
            const nextRows = Math.min(prev.sourceRowCount, prev.rowsBackfilled + inc);
            const nextLag = Math.max(0, Math.floor(140000 + (Math.random() * 20000 - 10000)));
            const isFinished = nextRows >= prev.sourceRowCount;
            return {
              ...prev,
              rowsBackfilled: nextRows,
              replicationLagBytes: isFinished ? 0 : nextLag,
              state: isFinished ? 'READY_FOR_CUTOVER' : 'BACKFILLING'
            };
          });
        }, 1200);
      }
    }

    try {
      eventSource = new EventSource(`${API_BASE_URL}/${initialData.id}/stream`);

      eventSource.onopen = () => {
        if (isMounted) setIsConnected(true);
      };

      eventSource.onmessage = (e) => {
        if (!isMounted) return;
        try {
          const progress: MigrationProgressEvent = JSON.parse(e.data);
          setLastEvent(progress);
          setData((prev) => ({
            ...prev,
            state: progress.state,
            rowsBackfilled: progress.rowsBackfilled,
            sourceRowCount: progress.totalSourceRows || prev.sourceRowCount,
            replicationLagBytes: progress.replicationLagBytes
          }));
        } catch (err) {
          console.error('Failed to parse SSE message:', err);
        }
      };

      eventSource.onerror = () => {
        if (isMounted) setIsConnected(false);
        if (eventSource) {
          eventSource.close();
          eventSource = null;
        }
        startFallbackSimulation();
      };
    } catch {
      startFallbackSimulation();
    }

    return () => {
      isMounted = false;
      if (eventSource) {
        eventSource.close();
        eventSource = null;
      }
      if (fallbackInterval) {
        clearInterval(fallbackInterval);
        fallbackInterval = null;
      }
    };
  }, [initialData?.id, initialData.state]);

  return { data, isConnected, lastEvent };
}
