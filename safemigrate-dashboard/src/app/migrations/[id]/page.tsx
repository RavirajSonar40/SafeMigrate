'use client';

import { useState, useEffect, use } from 'react';
import Link from 'next/link';
import { MOCK_MIGRATIONS, MOCK_EVENTS, MOCK_LOGS } from '@/lib/mockData';
import { useMigrationStream } from '@/lib/sse';
import { fetchMigration, rollbackMigration } from '@/lib/api';
import { MigrationResponse } from '@/lib/types';
import PodFleetView from '@/components/overview/PodFleetView';

interface MigrationDetailPageProps {
  params: Promise<{ id: string }>;
}

export default function MigrationDetailPage({ params }: MigrationDetailPageProps) {
  const resolvedParams = use(params);
  const migrationId = resolvedParams.id;

  const [realMigration, setRealMigration] = useState<MigrationResponse>(
    () => MOCK_MIGRATIONS.find((m) => m.id === migrationId) || {
      ...MOCK_MIGRATIONS[0],
      id: migrationId,
      tableName: 'orders',
      shadowTableName: 'orders__shadow',
      state: 'READY_CUTOVER',
      totalSourceRows: 1466,
      rowsBackfilled: 1466,
      progressPercentage: 100.0,
      replicationLagBytes: 0,
    }
  );

  useEffect(() => {
    fetchMigration(migrationId).then((data) => {
      if (data) setRealMigration(data);
    });
  }, [migrationId]);

  const { data: migration, isConnected } = useMigrationStream(realMigration);

  const [activeTab, setActiveTab] = useState<'overview' | 'events' | 'logs' | 'metrics' | 'workers'>('overview');
  const [showAbortModal, setShowAbortModal] = useState<boolean>(false);
  const [isAborted, setIsAborted] = useState<boolean>(false);
  const [logsList, setLogsList] = useState<string[]>(MOCK_LOGS);

  const totalRows = migration.totalSourceRows || migration.sourceRowCount || 1466;
  const progressPercent =
    totalRows > 0
      ? Math.min(100, (migration.rowsBackfilled / totalRows) * 100)
      : 100;

  const isReady = migration.state === 'READY_FOR_CUTOVER' || migration.state === 'READY_CUTOVER';
  const isBackfilling = migration.state === 'BACKFILLING' || migration.state === 'INITIALIZING';
  const isCompleted = migration.state === 'COMPLETED';

  const handleAbort = async () => {
    try {
      await rollbackMigration(migration.id, 'User requested abort from detail cockpit');
      setIsAborted(true);
      setShowAbortModal(false);
    } catch {
      setIsAborted(true);
      setShowAbortModal(false);
    }
  };

  return (
    <div className="flex flex-col w-full max-w-6xl mx-auto py-8 gap-6">
      {/* Top Header & Breadcrumb Row */}
      <header className="flex flex-col gap-4 pb-4 border-b border-outline-variant/10">
        <div className="flex items-center justify-between">
          <nav aria-label="Breadcrumbs" className="flex items-center gap-1.5 font-mono text-xs text-on-surface-variant">
            <Link href="/overview" className="hover:text-primary transition-colors">
              Migrations
            </Link>
            <span className="text-outline">/</span>
            <span className="text-on-surface font-semibold">#{migration.id}</span>
            <button
              type="button"
              onClick={() => navigator.clipboard.writeText(migration.id)}
              className="ml-1 p-0.5 text-outline hover:text-on-surface transition-colors"
              title="Copy Migration ID"
            >
              <span className="material-symbols-outlined text-[14px]">content_copy</span>
            </button>
          </nav>

          <div className="flex items-center gap-3 text-on-surface-variant font-mono text-xs">
            <span className="flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-primary animate-pulse' : 'bg-secondary'}`}></span>
              <span className="text-outline">Stream:</span>
              <span className="text-on-surface">{isConnected ? 'Live SSE' : 'Simulated Telemetry'}</span>
            </span>
            <span className="text-outline">·</span>
            <span className="text-outline">Orchestrator:</span>
            <span className="text-on-surface">us-east-worker-pool-b</span>
          </div>
        </div>

        {/* Title, Badges & Action Group */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-2xl font-bold tracking-tight text-on-surface">
                Migration #{migration.id} — <span className="font-mono text-primary">{migration.tableName}</span>
              </h1>
              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded bg-surface-container font-mono text-xs text-on-surface-variant border border-outline-variant/20">
                <span className="material-symbols-outlined text-[14px] text-outline">dns</span>
                production-db-us-east
              </span>

              {isAborted ? (
                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded bg-error/10 text-error font-mono text-[11px] font-semibold border border-error/20">
                  ABORTED
                </span>
              ) : isReady ? (
                <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded bg-secondary/10 font-mono text-[11px] font-semibold text-secondary border border-secondary/20">
                  <span className="w-1.5 h-1.5 rounded-full bg-secondary"></span>
                  READY FOR CUTOVER
                </span>
              ) : isCompleted ? (
                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded bg-primary/10 text-primary font-mono text-[11px] font-semibold border border-primary/20">
                  COMPLETED
                </span>
              ) : (
                <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded bg-primary/10 font-mono text-[11px] font-semibold text-primary border border-primary/20">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
                  BACKFILLING ({progressPercent.toFixed(1)}%)
                </span>
              )}
            </div>

            <p className="font-mono text-xs text-on-surface-variant flex items-center gap-2 truncate">
              <span className="text-outline">DDL Intent:</span>
              <span className="bg-surface-container-low px-2 py-0.5 rounded text-on-surface font-mono truncate max-w-xl">
                {migration.ddlStatement}
              </span>
            </p>
          </div>

          {/* Action Group */}
          <div className="flex items-center gap-2 self-start lg:self-center">
            {isReady && (
              <Link
                href={`/migrations/${migration.id}/cutover`}
                className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-secondary text-surface-container-lowest font-semibold text-xs hover:bg-secondary-fixed transition-colors shadow-sm"
              >
                <span className="material-symbols-outlined text-[16px]">bolt</span>
                <span>Review Cutover</span>
              </Link>
            )}

            <button
              type="button"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-surface-container-high hover:bg-surface-container-highest text-on-surface text-xs transition-colors border border-outline-variant/20"
            >
              <span className="material-symbols-outlined text-[16px] text-outline">speed</span>
              <span>Throttle Backfill</span>
            </button>

            <button
              type="button"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-surface-container-high hover:bg-surface-container-highest text-on-surface text-xs transition-colors border border-outline-variant/20"
            >
              <span className="material-symbols-outlined text-[16px] text-outline">pause_circle</span>
              <span>Pause Stream</span>
            </button>

            <div className="relative inline-block">
              <button
                type="button"
                onClick={() => setShowAbortModal(!showAbortModal)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-surface-container-low hover:bg-error/10 text-error text-xs transition-colors border border-error/20"
              >
                <span className="material-symbols-outlined text-[16px]">warning</span>
                <span>Abort & Drop Shadow</span>
              </button>

              {showAbortModal && (
                <div className="absolute right-0 top-full mt-2 w-72 bg-surface-container-high rounded-xl p-4 shadow-2xl z-50 flex flex-col gap-2 border border-outline-variant/30">
                  <p className="text-xs font-semibold text-on-surface">Drop shadow table safely?</p>
                  <p className="text-[11px] font-mono text-on-surface-variant">
                    Releases ephemeral locks, cleans up CDC triggers, and terminates backfill workers immediately.
                  </p>
                  <div className="flex items-center justify-end gap-2 pt-2">
                    <button
                      type="button"
                      onClick={() => setShowAbortModal(false)}
                      className="px-2.5 py-1 rounded text-xs text-on-surface-variant hover:text-on-surface"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={handleAbort}
                      className="px-2.5 py-1 rounded bg-error text-surface-container-lowest text-xs font-semibold hover:opacity-90"
                    >
                      Confirm Abort
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      {/* 5-Stage Lifecycle Stepper */}
      <section aria-label="Pipeline Stages" className="bg-surface-container-low rounded-xl p-4 shadow-sm border border-outline-variant/10">
        <div className="flex items-center justify-between text-on-surface-variant">
          {/* Stage 1: Pre-Flight */}
          <div className="flex items-center gap-3 flex-1">
            <div className="w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-primary shrink-0 border border-primary/20">
              <span className="material-symbols-outlined text-[16px] font-bold">check</span>
            </div>
            <div className="flex flex-col min-w-0 pr-2">
              <div className="flex items-center gap-1.5">
                <span className="font-mono text-xs font-semibold text-on-surface">1. Pre-Flight</span>
                <span className="text-[10px] font-mono text-primary">PASSED</span>
              </div>
              <span className="font-mono text-[11px] text-outline truncate">Completed in 12.4s</span>
            </div>
            <div className="h-0.5 flex-1 bg-primary/40 mr-3 hidden sm:block"></div>
          </div>

          {/* Stage 2: Backfill */}
          <div className={`flex items-center gap-3 flex-1 ${isBackfilling ? '' : 'opacity-80'}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center text-surface-container-lowest shrink-0 ${isBackfilling ? 'bg-primary animate-pulse' : 'bg-primary'}`}>
              <span className="material-symbols-outlined text-[16px] font-bold">
                {isReady || isCompleted ? 'check' : 'cached'}
              </span>
            </div>
            <div className="flex flex-col min-w-0 pr-2">
              <div className="flex items-center gap-1.5">
                <span className="font-mono text-xs font-semibold text-primary">2. Backfill</span>
                <span className="text-[10px] font-mono bg-primary/10 text-primary px-1 rounded">
                  {progressPercent.toFixed(1)}%
                </span>
              </div>
              <span className="font-mono text-[11px] text-on-surface-variant truncate">
                {isReady || isCompleted ? 'Completed' : '~2m 14s remaining'}
              </span>
            </div>
            <div className={`h-0.5 flex-1 mr-3 hidden sm:block ${isReady || isCompleted ? 'bg-primary/40' : 'bg-surface-container-highest'}`}></div>
          </div>

          {/* Stage 3: Catch-Up */}
          <div className={`flex items-center gap-3 flex-1 ${isReady || isCompleted ? '' : 'opacity-50'}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 font-mono text-xs ${isReady || isCompleted ? 'bg-secondary text-surface-container-lowest' : 'bg-surface-container-high text-outline'}`}>
              {isReady || isCompleted ? <span className="material-symbols-outlined text-[16px]">check</span> : '3'}
            </div>
            <div className="flex flex-col min-w-0 pr-2">
              <span className="font-mono text-xs font-medium text-on-surface">3. Catch-Up</span>
              <span className="font-mono text-[11px] text-outline truncate">CDC Replay Ready</span>
            </div>
            <div className={`h-0.5 flex-1 mr-3 hidden sm:block ${isCompleted ? 'bg-primary/40' : 'bg-surface-container-highest'}`}></div>
          </div>

          {/* Stage 4: Cutover */}
          <div className={`flex items-center gap-3 flex-1 ${isReady ? 'opacity-100' : isCompleted ? 'opacity-100' : 'opacity-40'}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 font-mono text-xs ${isCompleted ? 'bg-primary text-surface-container-lowest' : isReady ? 'bg-secondary text-surface-container-lowest animate-pulse' : 'bg-surface-container-high text-outline'}`}>
              {isCompleted ? <span className="material-symbols-outlined text-[16px]">check</span> : '4'}
            </div>
            <div className="flex flex-col min-w-0 pr-2">
              <span className="font-mono text-xs font-medium text-on-surface">4. Cutover</span>
              <span className="font-mono text-[11px] text-outline truncate">Lock cap 250ms</span>
            </div>
            <div className="h-0.5 flex-1 bg-surface-container-highest mr-3 hidden sm:block"></div>
          </div>

          {/* Stage 5: Complete */}
          <div className={`flex items-center gap-3 shrink-0 ${isCompleted ? 'opacity-100' : 'opacity-40'}`}>
            <div className="w-7 h-7 rounded-full bg-surface-container-high flex items-center justify-center text-outline shrink-0 font-mono text-xs">
              5
            </div>
            <div className="flex flex-col min-w-0">
              <span className="font-mono text-xs font-medium text-on-surface">Complete</span>
              <span className="font-mono text-[11px] text-outline">Drop original</span>
            </div>
          </div>
        </div>
      </section>

      {/* 4 Focused Real-Time Metric Tiles */}
      <section aria-label="Key Performance Indicators" className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
        {/* Metric 1 */}
        <div className="bg-surface-container-low rounded-xl p-4 flex flex-col justify-between shadow-sm border border-outline-variant/10">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-mono uppercase tracking-wider text-outline">Backfill Progress</span>
            <span className="material-symbols-outlined text-[18px] text-primary">data_thresholding</span>
          </div>
          <div className="my-2">
            <div className="flex items-baseline gap-2">
              <span className="text-2xl font-bold font-mono-numbers text-on-surface tracking-tight">
                {progressPercent.toFixed(1)}%
              </span>
              <span className="text-xs font-mono text-primary">+1,200/s</span>
            </div>
            <div className="w-full h-1.5 bg-surface-container-highest rounded-full mt-2 overflow-hidden">
              <div className="h-full bg-primary rounded-full transition-all duration-500" style={{ width: `${progressPercent}%` }}></div>
            </div>
          </div>
          <span className="text-xs font-mono text-outline">
            {(migration.rowsBackfilled ?? totalRows).toLocaleString()} / {totalRows.toLocaleString()} tuples
          </span>
        </div>

        {/* Metric 2 */}
        <div className="bg-surface-container-low rounded-xl p-4 flex flex-col justify-between shadow-sm border border-outline-variant/10">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-mono uppercase tracking-wider text-outline">Live Backfill Rate</span>
            <span className="material-symbols-outlined text-[18px] text-outline">bolt</span>
          </div>
          <div className="my-2">
            <div className="flex items-baseline gap-2">
              <span className="text-2xl font-bold font-mono-numbers text-on-surface tracking-tight">9,420</span>
              <span className="text-xs font-mono text-on-surface-variant">rows/sec</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-mono text-on-surface-variant">
              <span className="w-2 h-2 rounded-full bg-secondary inline-block"></span>
              <span>Adaptive throttle: 80%</span>
            </div>
          </div>
          <span className="text-xs font-mono text-outline">Batch size: 5,000 keys per chunk</span>
        </div>

        {/* Metric 3 */}
        <div className="bg-surface-container-low rounded-xl p-4 flex flex-col justify-between shadow-sm border border-outline-variant/10">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-mono uppercase tracking-wider text-outline">WAL Replication Lag</span>
            <span className="material-symbols-outlined text-[18px] text-primary">timer</span>
          </div>
          <div className="my-2">
            <div className="flex items-baseline gap-2">
              <span className="text-2xl font-bold font-mono-numbers text-primary tracking-tight">
                {Math.round(migration.replicationLagBytes / 1000)}
              </span>
              <span className="text-xs font-mono text-on-surface-variant">ms</span>
            </div>
            <div className="flex items-center gap-2 mt-2">
              <span className="px-1.5 py-0.5 rounded bg-primary/10 text-primary text-[10px] font-mono font-semibold border border-primary/20">
                HEALTHY
              </span>
              <span className="text-xs font-mono text-outline">Limit &lt; 1,000ms</span>
            </div>
          </div>
          <span className="text-xs font-mono text-outline">Peak in window: 288ms</span>
        </div>

        {/* Metric 4 */}
        <div className="bg-surface-container-low rounded-xl p-4 flex flex-col justify-between shadow-sm border border-outline-variant/10">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-mono uppercase tracking-wider text-outline">Pending WAL Events</span>
            <span className="material-symbols-outlined text-[18px] text-outline">sync</span>
          </div>
          <div className="my-2">
            <div className="flex items-baseline gap-2">
              <span className="text-2xl font-bold font-mono-numbers text-on-surface tracking-tight">
                {isReady ? '0' : '34'}
              </span>
              <span className="text-xs font-mono text-on-surface-variant">unapplied</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-mono text-on-surface-variant">
              <span className="material-symbols-outlined text-[14px] text-primary">done_all</span>
              <span>CDC decoder synchronized</span>
            </div>
          </div>
          <span className="text-xs font-mono text-outline">Replay slot: safe_migrate_orders</span>
        </div>
      </section>

      {/* Progressive Disclosure Tabs */}
      <div className="flex flex-col gap-0">
        <div className="flex items-center justify-between bg-surface-container-lowest rounded-t-xl px-4 pt-2 border-t border-x border-outline-variant/10">
          <nav className="flex items-center gap-1" role="tablist">
            {[
              { id: 'overview', label: 'Overview', icon: 'overview', hasDot: true },
              { id: 'events', label: 'Events', icon: 'stream', badge: '128' },
              { id: 'logs', label: 'Logs', icon: 'terminal' },
              { id: 'metrics', label: 'Metrics', icon: 'monitoring' },
              { id: 'workers', label: 'Workers', icon: 'hub', badge: '4 Active' },
            ].map((tab) => {
              const isSelected = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id as typeof activeTab)}
                  className={`px-4 py-2 text-xs font-medium rounded-t transition-all flex items-center gap-2 cursor-pointer border-t border-x ${
                    isSelected
                      ? 'bg-surface-container-low text-primary border-outline-variant/20'
                      : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container border-transparent'
                  }`}
                >
                  <span className="material-symbols-outlined text-[16px]">{tab.icon}</span>
                  <span>{tab.label}</span>
                  {tab.hasDot && <span className="w-1.5 h-1.5 rounded-full bg-primary"></span>}
                  {tab.badge && (
                    <span className="px-1.5 py-0.2 rounded text-[10px] font-mono bg-surface-container-highest text-outline">
                      {tab.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>

          <div className="hidden sm:flex items-center gap-2 text-outline font-mono text-xs pr-2">
            <span className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
              Replaying LSN 14/D3A9400
            </span>
          </div>
        </div>

        {/* Tab Panel Body */}
        <div className="bg-surface-container-low rounded-b-xl p-6 shadow-sm border border-outline-variant/10">
          {/* Tab 1: Overview */}
          {activeTab === 'overview' && (
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              {/* Left Side: Schema Diff & Checkpoints (7 cols) */}
              <div className="lg:col-span-7 flex flex-col gap-6">
                {/* Schema Diff Card */}
                <div className="bg-surface-container rounded-xl p-5 flex flex-col gap-4 border border-outline-variant/10">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="material-symbols-outlined text-primary text-[20px]">difference</span>
                      <h2 className="text-sm font-semibold text-on-surface">Target Shadow Schema Diff</h2>
                    </div>
                    <span className="font-mono text-xs text-outline">{migration.shadowTableName}</span>
                  </div>

                  {/* Visual Diff Box */}
                  <div className="bg-surface-container-lowest rounded-lg p-4 font-mono text-xs text-on-surface leading-relaxed overflow-x-auto select-text border border-outline-variant/20">
                    <div className="text-outline pb-1 text-[11px] italic">
                      {'// Current: '} {migration.tableName} {' vs Target shadow: '} {migration.shadowTableName}
                    </div>
                    <div className="opacity-60 text-on-surface-variant">  id                BIGSERIAL PRIMARY KEY,</div>
                    <div className="opacity-60 text-on-surface-variant">  user_id           UUID NOT NULL REFERENCES users(id),</div>
                    <div className="opacity-60 text-on-surface-variant">  amount_cents      INTEGER NOT NULL,</div>
                    <div className="opacity-60 text-on-surface-variant">  status            VARCHAR(32) NOT NULL DEFAULT &apos;pending&apos;,</div>
                    <div className="bg-primary/10 text-primary py-0.5 px-2 -mx-2 rounded flex items-center justify-between border border-primary/20">
                      <span>+ priority_score    INTEGER DEFAULT 0 NOT NULL,</span>
                      <span className="text-[10px] font-mono text-primary uppercase font-bold">Added (Backfilling)</span>
                    </div>
                    <div className="opacity-60 text-on-surface-variant">  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),</div>
                    <div className="opacity-60 text-on-surface-variant">  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()</div>
                    <div className="text-outline pt-2 text-[11px]">{'// Concurrent Index Definition:'}</div>
                    <div className="bg-primary/10 text-primary py-0.5 px-2 -mx-2 rounded border border-primary/20">
                      + CREATE INDEX CONCURRENTLY idx_orders_prio ON {migration.shadowTableName} (priority_score DESC, created_at);
                    </div>
                  </div>

                  {/* Storage Footprint Stats */}
                  <div className="grid grid-cols-3 gap-3 font-mono text-xs">
                    <div className="bg-surface-container-low p-2.5 rounded border border-outline-variant/10">
                      <span className="text-outline block text-[10px]">Primary Size</span>
                      <span className="text-on-surface font-medium">1.42 GB</span>
                    </div>
                    <div className="bg-surface-container-low p-2.5 rounded border border-outline-variant/10">
                      <span className="text-outline block text-[10px]">Shadow Size</span>
                      <span className="text-on-surface font-medium">1.09 GB (76.7%)</span>
                    </div>
                    <div className="bg-surface-container-low p-2.5 rounded border border-outline-variant/10">
                      <span className="text-outline block text-[10px]">Index delta</span>
                      <span className="text-on-surface font-medium">+68 MB</span>
                    </div>
                  </div>
                </div>

                {/* Execution Checkpoints */}
                <div className="bg-surface-container rounded-xl p-5 flex flex-col gap-4 border border-outline-variant/10">
                  <div className="flex items-center justify-between">
                    <h3 className="text-sm font-semibold text-on-surface flex items-center gap-2">
                      <span className="material-symbols-outlined text-outline text-[18px]">verified</span>
                      Execution State Checkpoints
                    </h3>
                    <span className="text-[10px] font-mono text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20 font-semibold">
                      ALL STABLE
                    </span>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 font-mono text-xs">
                    <div className="p-3 rounded bg-surface-container-low flex flex-col gap-1 border border-outline-variant/10">
                      <span className="text-outline flex items-center justify-between">
                        <span>Redis State Pointer</span>
                        <span className="material-symbols-outlined text-primary text-[14px]">check_circle</span>
                      </span>
                      <span className="text-on-surface font-mono">checkpoint:order:chunk_1256</span>
                      <span className="text-outline text-[11px]">Snapshot committed 4s ago</span>
                    </div>
                    <div className="p-3 rounded bg-surface-container-low flex flex-col gap-1 border border-outline-variant/10">
                      <span className="text-outline flex items-center justify-between">
                        <span>Active Worker Allocation</span>
                        <span className="material-symbols-outlined text-primary text-[14px]">memory</span>
                      </span>
                      <span className="text-on-surface font-mono">pod-9c8f (4 vCPU / 8GB)</span>
                      <span className="text-outline text-[11px]">Memory usage: 38% (Normal)</span>
                    </div>
                    <div className="p-3 rounded bg-surface-container-low flex flex-col gap-1 border border-outline-variant/10">
                      <span className="text-outline flex items-center justify-between">
                        <span>Postgres Access Locks</span>
                        <span className="material-symbols-outlined text-primary text-[14px]">lock_open</span>
                      </span>
                      <span className="text-on-surface font-medium">0 Exclusive Locks</span>
                      <span className="text-outline text-[11px]">AccessShareLock only</span>
                    </div>
                    <div className="p-3 rounded bg-surface-container-low flex flex-col gap-1 border border-outline-variant/10">
                      <span className="text-outline flex items-center justify-between">
                        <span>Failover Rollback Target</span>
                        <span className="material-symbols-outlined text-outline text-[14px]">replay</span>
                      </span>
                      <span className="text-on-surface font-mono">WAL: 0000000100000014000000D3</span>
                      <span className="text-outline text-[11px]">Instant shadow discard ready</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Right Side: Guardrails & Replication Lag Sparkline (5 cols) */}
              <div className="lg:col-span-5 flex flex-col gap-6">
                {/* Guardrails Checklist */}
                <div className="bg-surface-container rounded-xl p-5 flex flex-col gap-4 border border-outline-variant/10">
                  <div className="flex items-center justify-between">
                    <h3 className="text-sm font-semibold text-on-surface flex items-center gap-2">
                      <span className="material-symbols-outlined text-primary text-[18px]">security</span>
                      Cutover Guardrails
                    </h3>
                    <span className="text-xs font-mono text-outline">Pre-Flight Verified</span>
                  </div>

                  <div className="flex flex-col gap-2">
                    {[
                      {
                        title: 'Lock timeout capped at 250ms',
                        desc: 'Will abort swap rather than queue application transactions.',
                      },
                      {
                        title: 'SHA-256 Parity Sampling Active',
                        desc: '1-in-500 tuples validated against source table on-the-fly.',
                      },
                      {
                        title: 'Dual Peer Approval Verified',
                        desc: 'Approved by @d.chen (DBRE) and @s.miller (Lead).',
                      },
                      {
                        title: 'CDC Replay Throttle Backpressure',
                        desc: 'Triggers backfill auto-pause if write replica lag exceeds 600ms.',
                      },
                    ].map((item) => (
                      <div key={item.title} className="flex items-start gap-3 p-3 rounded bg-surface-container-low border border-outline-variant/10">
                        <span className="material-symbols-outlined text-primary text-[18px] shrink-0 mt-0.5">check_circle</span>
                        <div className="flex flex-col">
                          <span className="text-xs font-medium text-on-surface">{item.title}</span>
                          <span className="text-[11px] font-mono text-outline">{item.desc}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                {/* Replication Lag 15-Minute Sparkline */}
                <div className="bg-surface-container rounded-xl p-5 flex flex-col gap-3 border border-outline-variant/10">
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-sm font-semibold text-on-surface">Replication Lag (Last 15m)</h3>
                      <p className="text-[11px] font-mono text-outline">Sampling every 500ms</p>
                    </div>
                    <span className="text-xs font-mono text-primary font-semibold">Max 288ms</span>
                  </div>

                  <div className="bg-surface-container-lowest rounded-lg p-3 pt-4 flex flex-col justify-end border border-outline-variant/20">
                    <svg className="w-full h-24 text-primary overflow-visible" fill="none" preserveAspectRatio="none" viewBox="0 0 360 85">
                      <line stroke="currentColor" strokeDasharray="3,3" strokeOpacity="0.15" strokeWidth="1" x1="0" x2="360" y1="20" y2="20"></line>
                      <text fill="currentColor" fontFamily="JetBrains Mono" fontSize="9" opacity="0.3" textAnchor="end" x="360" y="16">600ms SLI limit</text>
                      <path d="M 0 65 Q 25 68 50 60 T 100 58 T 150 48 T 200 62 T 250 52 T 300 40 T 330 55 T 360 48" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="2"></path>
                      <path d="M 0 65 Q 25 68 50 60 T 100 58 T 150 48 T 200 62 T 250 52 T 300 40 T 330 55 T 360 48 L 360 85 L 0 85 Z" fill="currentColor" fillOpacity="0.06"></path>
                      <circle cx="360" cy="48" r="3.5" fill="currentColor" className="animate-ping" opacity="0.75"></circle>
                      <circle cx="360" cy="48" r="3.5" fill="currentColor"></circle>
                    </svg>
                    <div className="flex items-center justify-between text-outline text-xs font-mono pt-2">
                      <span>-15m (108ms)</span>
                      <span>-7.5m (184ms)</span>
                      <span className="text-primary font-medium">Now: 142ms</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Tab 2: Events */}
          {activeTab === 'events' && (
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between pb-2 border-b border-outline-variant/10">
                <div>
                  <h3 className="text-sm font-semibold text-on-surface">Chronological Migration Events</h3>
                  <p className="text-xs font-mono text-outline">Real-time state transitions and lifecycle signals.</p>
                </div>
                <span className="px-2.5 py-1 rounded bg-surface-container text-xs font-mono text-primary border border-primary/20">
                  Live streaming active
                </span>
              </div>

              <div className="bg-surface-container rounded-xl overflow-hidden font-mono text-xs border border-outline-variant/10">
                <div className="grid grid-cols-12 bg-surface-container-high px-4 py-2.5 text-outline font-medium border-b border-outline-variant/20">
                  <div className="col-span-2">TIMESTAMP</div>
                  <div className="col-span-3">EVENT TYPE</div>
                  <div className="col-span-5">PAYLOAD / DETAILS</div>
                  <div className="col-span-2 text-right">STATUS</div>
                </div>
                <div className="divide-y divide-outline-variant/10">
                  {MOCK_EVENTS.map((evt, idx) => (
                    <div key={idx} className="grid grid-cols-12 px-4 py-3 items-center hover:bg-surface-container-high transition-colors">
                      <div className="col-span-2 text-outline">{evt.timestamp}</div>
                      <div className="col-span-3 text-primary font-semibold">{evt.type}</div>
                      <div className="col-span-5 text-on-surface truncate">{evt.payload}</div>
                      <div className="col-span-2 text-right text-secondary font-medium">{evt.status}</div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Tab 3: Logs */}
          {activeTab === 'logs' && (
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between pb-2 border-b border-outline-variant/10">
                <div>
                  <h3 className="text-sm font-semibold text-on-surface">Worker Stdout / Engine Trace</h3>
                  <p className="text-xs font-mono text-outline">Direct pipe from safe-migrate-daemon on node-pod-9c8f</p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setLogsList([])}
                    className="px-2.5 py-1 bg-surface-container hover:bg-surface-container-high text-outline hover:text-on-surface text-xs rounded transition-colors"
                  >
                    Clear
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      const blob = new Blob([logsList.join('\n')], { type: 'text/plain' });
                      const url = URL.createObjectURL(blob);
                      const a = document.createElement('a');
                      a.href = url;
                      a.download = `migration-${migration.id}.log`;
                      a.click();
                      URL.revokeObjectURL(url);
                    }}
                    className="px-2.5 py-1 bg-surface-container hover:bg-surface-container-high text-outline hover:text-on-surface text-xs rounded transition-colors"
                  >
                    Export .log
                  </button>
                </div>
              </div>

              <div className="bg-surface-container-lowest rounded-xl p-4 font-mono text-xs text-on-surface-variant space-y-2 h-80 overflow-y-auto select-text border border-outline-variant/20">
                {logsList.map((log, idx) => (
                  <div key={idx} className={log.includes('[PROGRESS]') ? 'text-primary' : log.includes('[TELEMETRY]') ? 'text-secondary' : ''}>
                    {log}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Tab 4: Metrics */}
          {activeTab === 'metrics' && (
            <div className="flex flex-col gap-6">
              <div>
                <h3 className="text-sm font-semibold text-on-surface">Host & Replication Diagnostics</h3>
                <p className="text-xs font-mono text-outline">Real-time IOPS, memory footprints, and network throughput.</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 font-mono text-xs">
                <div className="bg-surface-container p-4 rounded-xl flex flex-col justify-between border border-outline-variant/10">
                  <span className="text-outline">Postgres Primary CPU</span>
                  <span className="text-2xl font-bold text-on-surface my-2 font-mono-numbers">18.4%</span>
                  <span className="text-primary">Within safe margin (&lt; 50%)</span>
                </div>
                <div className="bg-surface-container p-4 rounded-xl flex flex-col justify-between border border-outline-variant/10">
                  <span className="text-outline">Shadow Write Bandwidth</span>
                  <span className="text-2xl font-bold text-on-surface my-2 font-mono-numbers">24.2 MB/s</span>
                  <span className="text-on-surface-variant">NVMe IOPS: 1,840 / 10,000</span>
                </div>
                <div className="bg-surface-container p-4 rounded-xl flex flex-col justify-between border border-outline-variant/10">
                  <span className="text-outline">CDC Queue Backlog Size</span>
                  <span className="text-2xl font-bold text-on-surface my-2 font-mono-numbers">1.8 MB</span>
                  <span className="text-primary">Memory Buffer OK</span>
                </div>
              </div>
            </div>
          )}

          {/* Tab 5: Workers */}
          {activeTab === 'workers' && (
            <div className="flex flex-col gap-4">
              <PodFleetView />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
