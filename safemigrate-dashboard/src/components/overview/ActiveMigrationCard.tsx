'use client';

import Link from 'next/link';
import { MigrationResponse } from '@/lib/types';

interface ActiveMigrationCardProps {
  migration: MigrationResponse;
}

export default function ActiveMigrationCard({ migration }: ActiveMigrationCardProps) {
  const isBackfilling = migration.state === 'BACKFILLING';
  const isReadyForCutover = migration.state === 'READY_FOR_CUTOVER';

  const progressPercent = migration.sourceRowCount > 0
    ? Math.min(100, (migration.rowsBackfilled / migration.sourceRowCount) * 100)
    : 100;

  const rowsFormatted = `${(migration.rowsBackfilled / 1000000).toFixed(1)}M / ${(migration.sourceRowCount / 1000000).toFixed(1)}M`;

  return (
    <div className="p-6 rounded-xl bg-surface-container-low hover:bg-surface-container transition-all shadow-sm flex flex-col justify-between gap-6 border border-outline-variant/10">
      <div className="flex flex-col gap-4">
        {/* Card Header: ID, Table, Status Chip */}
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <span className="font-mono text-sm font-semibold text-on-surface">#{migration.id}</span>
              <span className="text-outline text-xs">/</span>
              <span className="font-mono text-xs text-on-surface-variant">{migration.database || 'production-db-us-east'}</span>
            </div>
            <div className="flex items-center gap-1 text-on-surface font-medium text-sm">
              <span className="material-symbols-outlined text-[16px] text-on-surface-variant">table_chart</span>
              <span>{migration.tableName}</span>
            </div>
          </div>

          {/* Status Badge */}
          {isBackfilling && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-surface-container text-xs font-mono text-primary border border-primary/20">
              <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
              Backfilling ({progressPercent.toFixed(1)}%)
            </span>
          )}

          {isReadyForCutover && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-secondary/10 text-xs font-mono text-secondary border border-secondary/20">
              <span className="w-1.5 h-1.5 rounded-full bg-secondary"></span>
              Ready for Cutover
            </span>
          )}
        </div>

        {/* DDL Snippet */}
        <div className="px-3 py-2 rounded-lg bg-surface-container-lowest font-mono text-xs text-on-surface-variant flex items-center gap-2 truncate border border-outline-variant/20">
          <span className="material-symbols-outlined text-[16px] text-primary">
            {isReadyForCutover ? 'key' : 'edit_square'}
          </span>
          <span className="text-on-surface truncate">{migration.ddlStatement}</span>
        </div>

        {/* Progress Meter */}
        <div className="flex flex-col gap-1.5">
          <div className="flex justify-between items-center text-xs">
            <span className="text-on-surface-variant">
              {isBackfilling
                ? `Synchronizing rows (${rowsFormatted})`
                : 'Logical CDC stream synced (0 lag)'}
            </span>
            <span className="font-mono text-on-surface font-medium">
              {isBackfilling ? 'ETA: ~2m 14s' : '100% Synced'}
            </span>
          </div>
          <div className="w-full h-1.5 rounded-full bg-surface-container-highest overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-500 ${
                isReadyForCutover ? 'bg-secondary' : 'bg-primary'
              }`}
              style={{ width: `${progressPercent}%` }}
            ></div>
          </div>
        </div>
      </div>

      {/* Card Footer Actions */}
      <div className="flex items-center justify-between pt-2 border-t border-outline-variant/10">
        <span className="font-mono text-xs text-on-surface-variant">
          {isBackfilling ? 'Worker node: worker-us-04' : 'Lock timeout ceiling: 150ms'}
        </span>

        {isReadyForCutover ? (
          <Link
            href={`/migrations/${migration.id}/cutover`}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-secondary text-surface-container-lowest text-xs font-semibold hover:bg-secondary-fixed transition-colors shadow-sm"
          >
            <span>Review Cutover</span>
            <span className="material-symbols-outlined text-[16px]">arrow_forward</span>
          </Link>
        ) : (
          <Link
            href={`/migrations/${migration.id}`}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-on-surface text-xs font-medium transition-colors border border-outline-variant/20"
          >
            <span>Open Details</span>
            <span className="material-symbols-outlined text-[16px]">arrow_forward</span>
          </Link>
        )}
      </div>
    </div>
  );
}
