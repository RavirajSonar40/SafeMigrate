import React from 'react';

interface MetricsStripProps {
  activeCount?: number;
  completedCount?: number;
  failedCount?: number;
}

export default function MetricsStrip({
  activeCount = 0,
  completedCount = 0,
  failedCount = 0,
}: MetricsStripProps) {
  return (
    <section aria-label="System Metrics" className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {/* Metric 1: Active Migrations */}
      <div className="p-6 rounded-xl bg-surface-container-low shadow-sm flex flex-col justify-between h-36 border border-outline-variant/10">
        <div className="flex items-center justify-between">
          <span className="text-sm text-on-surface-variant">Active Migrations</span>
          <span className="p-1.5 rounded-lg bg-surface-container text-primary">
            <span className="material-symbols-outlined text-[18px]">cached</span>
          </span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-3xl font-semibold text-on-surface leading-none font-mono-numbers">
            {activeCount}
          </span>
          <span className="text-xs text-on-surface-variant">
            {activeCount === 0 ? 'Cluster idle · ready for schema change' : `${activeCount} live in progress`}
          </span>
        </div>
      </div>

      {/* Metric 2: Completed (30d) */}
      <div className="p-6 rounded-xl bg-surface-container-low shadow-sm flex flex-col justify-between h-36 border border-outline-variant/10">
        <div className="flex items-center justify-between">
          <span className="text-sm text-on-surface-variant">Completed (30d)</span>
          <span className="p-1.5 rounded-lg bg-surface-container text-secondary">
            <span className="material-symbols-outlined text-[18px]">task_alt</span>
          </span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-3xl font-semibold text-on-surface leading-none font-mono-numbers">
            {completedCount}
          </span>
          <span className="text-xs text-secondary flex items-center gap-1 font-medium">
            <span className="material-symbols-outlined text-[14px]">verified</span>
            100% zero data loss
          </span>
        </div>
      </div>

      {/* Metric 3: Failed / Rolled Back */}
      <div className="p-6 rounded-xl bg-surface-container-low shadow-sm flex flex-col justify-between h-36 border border-outline-variant/10">
        <div className="flex items-center justify-between">
          <span className="text-sm text-on-surface-variant">Failed / Rolled Back</span>
          <span className="p-1.5 rounded-lg bg-surface-container text-primary">
            <span className="material-symbols-outlined text-[18px]">check_circle</span>
          </span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-3xl font-semibold text-on-surface leading-none font-mono-numbers">
            {failedCount}
          </span>
          <span className="text-xs text-on-surface-variant">Clean rollbacks · zero incident</span>
        </div>
      </div>

      {/* Metric 4: Health Overview */}
      <div className="p-6 rounded-xl bg-surface-container-low shadow-sm flex flex-col justify-between h-36 border border-outline-variant/10">
        <div className="flex items-center justify-between">
          <span className="text-sm text-on-surface-variant">System Health</span>
          <span className="inline-flex items-center gap-1 text-[11px] font-mono font-semibold px-2 py-0.5 rounded bg-primary/10 text-primary uppercase">
            HEALTHY
          </span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-lg font-semibold text-on-surface leading-snug">All Systems Nominal</span>
          <span className="text-xs text-on-surface-variant">PG Replication · Stream · Workers</span>
        </div>
      </div>
    </section>
  );
}
