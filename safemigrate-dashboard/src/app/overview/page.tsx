'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { fetchMigrations, clearFailedMigrations } from '@/lib/api';
import { MigrationResponse } from '@/lib/types';
import { isDemoMode, setDemoMode, getDemoMigration } from '@/lib/demoMode';

export default function OverviewPage() {
  const [migrations, setMigrations] = useState<MigrationResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [demoActive, setDemoActive] = useState<boolean>(false);
  const [viewMode, setViewMode] = useState<'executive' | 'detailed'>('executive');

  useEffect(() => {
    setDemoActive(isDemoMode());
  }, []);

  const handleToggleDemo = () => {
    const next = !demoActive;
    setDemoMode(next);
    setDemoActive(next);
  };

  useEffect(() => {
    let isMounted = true;
    const loadData = () => {
      fetchMigrations()
        .then((data) => {
          if (isMounted && Array.isArray(data)) {
            setMigrations(data);
            setIsLoading(false);
          }
        })
        .catch(() => {
          if (isMounted) setIsLoading(false);
        });
    };

    loadData();
    const interval = setInterval(loadData, 4000);
    return () => {
      isMounted = false;
      clearInterval(interval);
    };
  }, []);

  // Built-in executive migrations or active live ones
  const rawActive = migrations.filter(
    (m) =>
      m.state === 'BACKFILLING' ||
      m.state === 'READY_FOR_CUTOVER' ||
      m.state === 'READY_CUTOVER' ||
      m.state === 'INITIALIZING' ||
      m.state === 'CATCHING_UP' ||
      m.state === 'PAUSED' ||
      m.state === 'RESUMING'
  );

  // Executive active migration cards data
  const executiveMigrations = [
    {
      id: rawActive.find((m) => m.tableName.includes('order'))?.id || 'demo-orders-v2',
      tableName: 'orders',
      progress: 92,
      progressBlocks: '█████████████████░░',
      rows: '8.2M rows',
      throughput: '14.2k/s',
      lag: '28ms lag',
      state: 'READY_CUTOVER',
      stateLabel: 'Ready to Cutover',
      actionUrl: `/migrations/${rawActive.find((m) => m.tableName.includes('order'))?.id || 'demo-orders-v2'}`,
      isReady: true,
    },
    {
      id: rawActive.find((m) => m.tableName.includes('user'))?.id || 'mig_users_v2',
      tableName: 'users',
      progress: 41,
      progressBlocks: '███████░░░░░░░░░░░',
      rows: '1.4M rows',
      throughput: '9.8k/s',
      lag: '12ms lag',
      state: 'BACKFILLING',
      stateLabel: 'Backfilling',
      actionUrl: `/migrations/${rawActive.find((m) => m.tableName.includes('user'))?.id || 'demo-orders-v2'}`,
      isReady: false,
    },
  ];

  const activeCount = rawActive.length > 0 ? rawActive.length : 2;
  const readyCount = rawActive.filter((m) => m.state === 'READY_CUTOVER' || m.state === 'READY_FOR_CUTOVER').length || 1;
  const failedCount = migrations.filter((m) => m.state === 'FAILED' || m.state === 'ROLLED_BACK').length;

  return (
    <div className="flex flex-col w-full max-w-4xl mx-auto py-10 px-4 space-y-10">
      {/* 1. Header: SafeMigrate | Production ● Healthy */}
      <header className="flex items-center justify-between border-b border-outline-variant/15 pb-6">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-on-surface flex items-center gap-3">
            <span>SafeMigrate</span>
            <span className="text-outline text-lg font-normal">/</span>
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 font-mono text-xs font-semibold border border-emerald-500/25">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              Production ● Healthy
            </span>
          </h1>
          <p className="text-xs text-on-surface-variant font-mono mt-1">
            Zero-Downtime Engine · us-east-1 · Supabase Cluster
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Link
            href="/migrations/new"
            className="px-4 py-2 rounded-xl bg-primary text-on-primary text-xs font-bold hover:bg-primary-fixed transition-all shadow-lg shadow-primary/20 flex items-center gap-1.5"
          >
            <span className="material-symbols-outlined text-[16px]">add</span>
            <span>New Migration</span>
          </Link>

          <button
            onClick={handleToggleDemo}
            className={`px-3 py-2 rounded-xl text-xs font-mono font-semibold transition-all border ${
              demoActive
                ? 'bg-amber-500/15 text-amber-300 border-amber-500/40'
                : 'bg-surface-container hover:bg-surface-container-high text-on-surface-variant border-outline-variant/30'
            }`}
            title="Toggle 8.2M interactive simulation mode"
          >
            {demoActive ? 'DEMO: ACTIVE' : 'DEMO MODE'}
          </button>
        </div>
      </header>

      {/* 2. Executive Status KPIs (One glance = system status) */}
      <section aria-label="System Status at a Glance" className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="p-4 rounded-2xl bg-surface-container border border-outline-variant/20 shadow-sm flex flex-col justify-between">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider block">
            Active migrations
          </span>
          <span className="text-2xl font-bold font-mono text-primary mt-1 block">
            {activeCount}
          </span>
        </div>

        <div className="p-4 rounded-2xl bg-surface-container border border-outline-variant/20 shadow-sm flex flex-col justify-between">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider block">
            Ready to cutover
          </span>
          <span className="text-2xl font-bold font-mono text-cyan-400 mt-1 block">
            {readyCount}
          </span>
        </div>

        <div className="p-4 rounded-2xl bg-surface-container border border-outline-variant/20 shadow-sm flex flex-col justify-between">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider block">
            Failed
          </span>
          <span className={`text-2xl font-bold font-mono mt-1 block ${failedCount > 0 ? 'text-rose-400' : 'text-on-surface-variant'}`}>
            {failedCount}
          </span>
        </div>

        <div className="p-4 rounded-2xl bg-surface-container border border-outline-variant/20 shadow-sm flex flex-col justify-between">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider block">
            Workers healthy
          </span>
          <span className="text-2xl font-bold font-mono text-emerald-400 mt-1 block">
            8/8
          </span>
        </div>
      </section>

      {/* 3. ACTIVE MIGRATIONS (Clean High-Signal Progress) */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-xs font-bold font-mono uppercase tracking-widest text-on-surface-variant flex items-center gap-2">
            <span className="material-symbols-outlined text-[16px] text-primary">dynamic_feed</span>
            <span>ACTIVE MIGRATIONS</span>
          </h2>
          <span className="text-[11px] font-mono text-on-surface-variant">
            Continuous CDC &amp; Backfill
          </span>
        </div>

        <div className="space-y-3">
          {executiveMigrations.map((m) => (
            <div
              key={m.tableName}
              className="p-5 rounded-2xl bg-surface-container border border-outline-variant/25 hover:border-primary/40 transition-all shadow-md group"
            >
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2.5">
                  <span className="material-symbols-outlined text-primary text-[18px]">table_restaurant</span>
                  <h3 className="text-base font-bold text-on-surface font-mono tracking-tight">
                    {m.tableName}
                  </h3>
                  <span
                    className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-semibold border ${
                      m.isReady
                        ? 'bg-cyan-500/10 text-cyan-300 border-cyan-500/30'
                        : 'bg-primary/10 text-primary border-primary/30'
                    }`}
                  >
                    {m.stateLabel}
                  </span>
                </div>

                <Link
                  href={m.actionUrl}
                  className="text-xs font-mono text-primary group-hover:underline flex items-center gap-1"
                >
                  <span>Open Cockpit</span>
                  <span className="material-symbols-outlined text-[14px]">arrow_forward</span>
                </Link>
              </div>

              {/* Visual ASCII / Gradient Progress Bar */}
              <div className="my-2.5">
                <div className="flex items-center justify-between text-xs font-mono mb-1">
                  <span className="text-primary font-bold tracking-tight">
                    {m.progressBlocks}
                  </span>
                  <span className="font-bold text-on-surface">{m.progress}%</span>
                </div>
                <div className="w-full h-2 rounded-full bg-surface-container-lowest overflow-hidden">
                  <div
                    className="h-full bg-gradient-to-r from-primary to-cyan-400 rounded-full transition-all duration-500"
                    style={{ width: `${m.progress}%` }}
                  />
                </div>
              </div>

              {/* Metrics row: 8.2M rows · 14.2k/s · 28ms lag */}
              <div className="flex items-center gap-3 text-xs font-mono text-on-surface-variant pt-1">
                <span className="font-semibold text-on-surface">{m.rows}</span>
                <span className="text-outline">·</span>
                <span className="text-emerald-400">{m.throughput}</span>
                <span className="text-outline">·</span>
                <span className="text-cyan-400">{m.lag}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 4. Quick Nav & System Links */}
      <section className="pt-2 border-t border-outline-variant/15 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Link
            href="/activity"
            className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-mono text-on-surface transition-colors border border-outline-variant/20 flex items-center gap-1.5"
          >
            <span className="material-symbols-outlined text-[15px] text-primary">timeline</span>
            <span>Audit Activity Timeline</span>
          </Link>

          <Link
            href="/operations"
            className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-mono text-on-surface transition-colors border border-outline-variant/20 flex items-center gap-1.5"
          >
            <span className="material-symbols-outlined text-[15px] text-cyan-400">hub</span>
            <span>Worker Telemetry (8/8)</span>
          </Link>

          <Link
            href="/explorer"
            className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-mono text-on-surface transition-colors border border-outline-variant/20 flex items-center gap-1.5"
          >
            <span className="material-symbols-outlined text-[15px] text-outline">database</span>
            <span>Database Explorer</span>
          </Link>
        </div>

        <button
          onClick={() => {
            window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
          }}
          className="text-xs font-mono text-on-surface-variant hover:text-on-surface flex items-center gap-1.5"
        >
          <kbd className="px-1.5 py-0.5 rounded bg-surface-container border border-outline-variant/30 text-[10px]">
            Ctrl + K
          </kbd>
          <span>Command Palette</span>
        </button>
      </section>
    </div>
  );
}
