'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import MetricsStrip from '@/components/overview/MetricsStrip';
import ActiveMigrationCard from '@/components/overview/ActiveMigrationCard';
import RecentMigrationsTable from '@/components/overview/RecentMigrationsTable';
import ClusterTopologyBar from '@/components/overview/ClusterTopologyBar';
import PodFleetView from '@/components/overview/PodFleetView';
import { fetchMigrations, clearFailedMigrations } from '@/lib/api';
import { MigrationResponse } from '@/lib/types';
import { isDemoMode, setDemoMode, getDemoMigration } from '@/lib/demoMode';

export default function OverviewPage() {
  const [migrations, setMigrations] = useState<MigrationResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [demoActive, setDemoActive] = useState<boolean>(false);

  useEffect(() => {
    setDemoActive(isDemoMode());
  }, []);

  const handleClearFailed = async () => {
    await clearFailedMigrations();
    setMigrations((prev) => prev.filter((m) => m.state !== 'FAILED' && m.state !== 'ROLLED_BACK'));
  };

  const handleToggleDemo = () => {
    const next = !demoActive;
    setDemoMode(next);
    setDemoActive(next);
  };

  useEffect(() => {
    let isMounted = true;
    const loadData = () => {
      fetchMigrations().then((data) => {
        if (isMounted && Array.isArray(data)) {
          setMigrations(data);
          setIsLoading(false);
        }
      }).catch(() => {
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

  // If demo mode is enabled and demo migration isn't in list, inject demo migration
  const activeMigrations = demoActive && !rawActive.some((m) => m.id === 'demo-orders-v2')
    ? [getDemoMigration(), ...rawActive]
    : rawActive;

  const completedMigrations = migrations.filter((m) => m.state === 'COMPLETED');
  const failedMigrations = migrations.filter((m) => m.state === 'ROLLED_BACK' || m.state === 'FAILED');

  return (
    <div className="flex flex-col w-full max-w-7xl mx-auto py-6 gap-8">
      {/* Top Action & Context Header */}
      <header className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 pb-4 border-b border-outline-variant/15">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2 text-primary">
            <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
            <span className="text-[11px] font-mono uppercase tracking-widest text-primary font-semibold">
              Cluster Orchestrator · us-east-1 · v2.4 Engine
            </span>
          </div>
          <h1 className="text-3xl font-bold tracking-tight text-on-surface">SafeMigrate Platform</h1>
          <p className="text-sm text-on-surface-variant">
            Zero-downtime PostgreSQL schema migrations with logical CDC, chunked backfill &amp; failure resilience.
          </p>
        </div>

        <div className="flex items-center gap-3">
          {/* Interactive Demo Mode Toggle Pill */}
          <button
            onClick={handleToggleDemo}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-xl text-xs font-mono transition-all border ${
              demoActive
                ? 'bg-amber-500/15 text-amber-300 border-amber-500/40 shadow-md shadow-amber-500/10'
                : 'bg-surface-container hover:bg-surface-container-high text-on-surface-variant border-outline-variant/30'
            }`}
            title="Toggle 8.2M row interactive demo simulation"
          >
            <span className="material-symbols-outlined text-[16px] text-amber-400">
              {demoActive ? 'sports_esports' : 'smart_toy'}
            </span>
            <span className="font-bold">{demoActive ? 'DEMO: ACTIVE' : 'DEMO MODE'}</span>
          </button>

          <Link
            href="/operations"
            className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-surface-container hover:bg-surface-container-high text-on-surface transition-colors text-xs font-semibold border border-outline-variant/30"
          >
            <span className="material-symbols-outlined text-[18px] text-cyan-400">hub</span>
            <span>Workers</span>
          </Link>

          <Link
            href="/migrations/new"
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-primary text-on-primary text-xs font-bold hover:bg-primary-fixed transition-all shadow-lg shadow-primary/25"
          >
            <span className="material-symbols-outlined text-[18px]">add</span>
            <span>New Migration</span>
          </Link>
        </div>
      </header>

      {/* Interactive Demo Banner when Demo is active */}
      {demoActive && (
        <div className="p-4 rounded-2xl bg-gradient-to-r from-amber-500/15 via-primary/10 to-surface-container-low border border-amber-500/30 flex flex-col md:flex-row items-start md:items-center justify-between gap-4 animate-in fade-in duration-300">
          <div className="flex items-start gap-3">
            <span className="material-symbols-outlined text-amber-400 text-2xl mt-0.5">bolt</span>
            <div>
              <span className="text-xs font-bold text-amber-300 uppercase tracking-wider block font-mono">
                Interactive Resilience Simulation Running
              </span>
              <p className="text-xs text-on-surface mt-0.5 max-w-2xl leading-relaxed">
                Simulating an 8.2M row production <code className="text-primary font-mono">orders</code> table with continuous CDC replication, interactive chaos injection (kill workers/pause Kafka), and automated checkpoint recovery.
              </p>
            </div>
          </div>
          <Link
            href="/migrations/demo-orders-v2"
            className="px-4 py-2 rounded-xl bg-amber-500 text-black text-xs font-bold hover:bg-amber-400 transition-colors shadow-md shrink-0"
          >
            Open Demo Cockpit →
          </Link>
        </div>
      )}

      {/* 4 Elevated KPIs */}
      <MetricsStrip 
        activeCount={activeMigrations.length} 
        completedCount={completedMigrations.length} 
        failedCount={failedMigrations.length} 
        onClearFailed={handleClearFailed}
      />

      {/* Active Migrations Section */}
      <section className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold text-on-surface tracking-tight">Active Migrations</h2>
            <span className="px-2 py-0.5 rounded text-[11px] font-mono bg-primary/10 text-primary uppercase font-bold border border-primary/20">
              {activeMigrations.length} Running
            </span>
          </div>
          <span className="text-xs font-mono text-on-surface-variant flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 inline-block animate-ping" />
            Cluster Replication Slot Synced
          </span>
        </div>

        {isLoading ? (
          <div className="p-8 rounded-2xl bg-surface-container border border-outline-variant/20 flex items-center justify-center gap-3">
            <span className="material-symbols-outlined text-primary text-[24px] animate-spin">sync</span>
            <span className="text-xs font-mono text-on-surface-variant">Connecting to SafeMigrate Cluster Orchestrator...</span>
          </div>
        ) : activeMigrations.length === 0 ? (
          <div className="p-8 rounded-2xl bg-surface-container border border-outline-variant/20 flex flex-col items-center text-center gap-3">
            <span className="material-symbols-outlined text-[36px] text-primary">database</span>
            <div className="flex flex-col">
              <span className="text-sm font-bold text-on-surface">Target Database Ready</span>
              <span className="text-xs text-on-surface-variant">Connected to Supabase PostgreSQL cluster. No active migrations running.</span>
            </div>
            <div className="flex items-center gap-2 mt-2">
              <button
                onClick={handleToggleDemo}
                className="px-4 py-2 rounded-xl bg-surface-container-high hover:bg-surface-container-highest text-primary text-xs font-bold transition-all border border-primary/30 flex items-center gap-1.5"
              >
                <span className="material-symbols-outlined text-[16px]">play_circle</span>
                <span>Launch 8.2M Row Demo</span>
              </button>
              <Link
                href="/migrations/new"
                className="px-4 py-2 rounded-xl bg-primary text-on-primary text-xs font-bold hover:bg-primary-fixed transition-all"
              >
                Launch Custom Migration
              </Link>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {activeMigrations.map((migration) => (
              <ActiveMigrationCard key={migration.id} migration={migration} />
            ))}
          </div>
        )}
      </section>

      {/* Docker Desktop & Pod Fleet Live Monitoring */}
      <PodFleetView />

      {/* Feature Navigation Quick Bar */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Link
          href="/operations"
          className="p-5 rounded-2xl bg-surface-container border border-outline-variant/20 hover:border-primary/40 transition-all shadow-md group flex items-start gap-3.5"
        >
          <div className="w-10 h-10 rounded-xl bg-cyan-500/10 border border-cyan-500/25 flex items-center justify-center text-cyan-400 group-hover:scale-105 transition-transform">
            <span className="material-symbols-outlined text-2xl">hub</span>
          </div>
          <div>
            <h3 className="text-xs font-bold text-on-surface group-hover:text-cyan-400 transition-colors">
              Cluster Worker Pool
            </h3>
            <p className="text-[11px] text-on-surface-variant mt-0.5">
              Inspect parallel backfill key range workers, WAL logical replication readers, and Redisson locks.
            </p>
          </div>
        </Link>

        <Link
          href="/explorer"
          className="p-5 rounded-2xl bg-surface-container border border-outline-variant/20 hover:border-primary/40 transition-all shadow-md group flex items-start gap-3.5"
        >
          <div className="w-10 h-10 rounded-xl bg-primary/10 border border-primary/25 flex items-center justify-center text-primary group-hover:scale-105 transition-transform">
            <span className="material-symbols-outlined text-2xl">table_chart</span>
          </div>
          <div>
            <h3 className="text-xs font-bold text-on-surface group-hover:text-primary transition-colors">
              Database Explorer
            </h3>
            <p className="text-[11px] text-on-surface-variant mt-0.5">
              Browse table catalogs, inspect column schemas, view live row counts, and execute verification queries.
            </p>
          </div>
        </Link>

        <div
          onClick={() => {
            window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
          }}
          className="p-5 rounded-2xl bg-surface-container border border-outline-variant/20 hover:border-primary/40 transition-all shadow-md group flex items-start gap-3.5 cursor-pointer"
        >
          <div className="w-10 h-10 rounded-xl bg-purple-500/10 border border-purple-500/25 flex items-center justify-center text-purple-400 group-hover:scale-105 transition-transform">
            <span className="material-symbols-outlined text-2xl">search</span>
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-xs font-bold text-on-surface group-hover:text-purple-400 transition-colors">
                Command Palette
              </h3>
              <kbd className="px-1.5 py-0.2 rounded bg-surface-container-high border border-outline-variant/30 text-[10px] font-mono text-on-surface-variant">
                Ctrl + K
              </kbd>
            </div>
            <p className="text-[11px] text-on-surface-variant mt-0.5">
              Instantly jump between migrations, trigger chaos tests, inspect workers, or plan new DDL runs.
            </p>
          </div>
        </div>
      </div>

      {/* Recent Migrations Table */}
      <RecentMigrationsTable migrations={migrations} />

      {/* Cluster Operational Baseline Bar */}
      <ClusterTopologyBar />
    </div>
  );
}
