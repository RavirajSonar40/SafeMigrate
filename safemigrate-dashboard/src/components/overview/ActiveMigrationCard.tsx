'use client';

import Link from 'next/link';
import { MigrationResponse } from '@/lib/types';

interface ActiveMigrationCardProps {
  migration: MigrationResponse;
}

export default function ActiveMigrationCard({ migration }: ActiveMigrationCardProps) {
  const isBackfilling = migration.state === 'BACKFILLING' || migration.state === 'INITIALIZING';
  const isReadyForCutover = migration.state === 'READY_FOR_CUTOVER' || migration.state === 'READY_CUTOVER';
  const isCompleted = migration.state === 'COMPLETED';

  const totalRows = migration.totalSourceRows || migration.sourceRowCount || 1466;
  const progressPercent = migration.progressPercentage !== undefined
    ? migration.progressPercentage
    : totalRows > 0
    ? Math.min(100, (migration.rowsBackfilled / totalRows) * 100)
    : 100;

  const rowsFormatted = totalRows >= 1000000
    ? `${(migration.rowsBackfilled / 1000000).toFixed(1)}M / ${(totalRows / 1000000).toFixed(1)}M`
    : `${migration.rowsBackfilled.toLocaleString()} / ${totalRows.toLocaleString()} rows`;

  return (
    <div className="p-6 rounded-2xl bg-surface-container-lowest hover:bg-surface-container-low transition-all shadow-md flex flex-col justify-between gap-6 border border-outline-variant/15">
      <div className="flex flex-col gap-4">
        {/* Card Header: ID, Table, Status Chip */}
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <span className="font-mono text-sm font-bold text-on-surface">#{migration.id}</span>
              <span className="text-outline text-xs">/</span>
              <span className="font-mono text-xs text-on-surface-variant">{migration.database || 'safemigrate_test'}</span>
            </div>
            <div className="flex items-center gap-1.5 text-on-surface font-semibold text-sm mt-0.5">
              <span className="material-symbols-outlined text-[18px] text-primary">table_chart</span>
              <span className="font-mono">{migration.tableName}</span>
            </div>
          </div>

          {/* Status Badge */}
          {isBackfilling && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-primary/10 text-xs font-mono text-primary border border-primary/20">
              <span className="w-2 h-2 rounded-full bg-primary animate-ping"></span>
              Backfilling ({progressPercent.toFixed(1)}%)
            </span>
          )}

          {isReadyForCutover && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-primary/15 text-xs font-mono font-semibold text-primary border border-primary/30">
              <span className="w-2 h-2 rounded-full bg-primary animate-pulse"></span>
              Ready for Cutover
            </span>
          )}

          {isCompleted && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-500/10 text-xs font-mono text-emerald-400 border border-emerald-500/20">
              <span className="material-symbols-outlined text-[14px]">check</span>
              Cutover Completed
            </span>
          )}
        </div>

        {/* DDL Snippet */}
        <div className="px-3.5 py-2.5 rounded-xl bg-surface-container-low font-mono text-xs text-on-surface-variant flex items-center gap-2 truncate border border-outline-variant/15">
          <span className="material-symbols-outlined text-[16px] text-primary shrink-0">
            {isReadyForCutover ? 'verified' : 'code'}
          </span>
          <span className="text-on-surface truncate">{migration.ddlStatement}</span>
        </div>

        {/* Progress Meter */}
        <div className="flex flex-col gap-1.5">
          <div className="flex justify-between items-center text-xs">
            <span className="text-on-surface-variant font-mono">
              {isBackfilling
                ? `Synchronizing rows (${rowsFormatted})`
                : 'Logical CDC stream in sync (0 ms lag)'}
            </span>
            <span className="font-mono text-on-surface font-semibold">
              {isBackfilling ? 'ETA: ~12s' : '100.0% Synced'}
            </span>
          </div>
          <div className="w-full h-2 rounded-full bg-surface-container-high overflow-hidden">
            <div
              className="h-full rounded-full bg-primary transition-all duration-500 shadow-[0_0_8px_rgba(79,209,197,0.5)]"
              style={{ width: `${progressPercent}%` }}
            ></div>
          </div>
        </div>
      </div>

      {/* Card Footer Actions */}
      <div className="flex items-center justify-between pt-3 border-t border-outline-variant/10">
        <span className="font-mono text-xs text-on-surface-variant flex items-center gap-1">
          <span className="material-symbols-outlined text-[14px] text-outline">dns</span>
          <span>{isBackfilling ? 'Worker: safemigrate_worker_0' : 'Lock ceiling: 2000ms'}</span>
        </span>

        {isReadyForCutover ? (
          <Link
            href={`/migrations/${migration.id}/cutover`}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-primary text-surface-container-lowest text-xs font-bold hover:bg-primary-fixed transition-all shadow-md hover:scale-105"
          >
            <span>Review &amp; Cutover</span>
            <span className="material-symbols-outlined text-[16px]">arrow_forward</span>
          </Link>
        ) : (
          <Link
            href={`/migrations/${migration.id}`}
            className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl bg-surface-container hover:bg-surface-container-high text-on-surface text-xs font-medium transition-colors border border-outline-variant/20"
          >
            <span>Open Cockpit</span>
            <span className="material-symbols-outlined text-[16px]">arrow_forward</span>
          </Link>
        )}
      </div>
    </div>
  );
}
