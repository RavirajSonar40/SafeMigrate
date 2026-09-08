'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import MetricsStrip from '@/components/overview/MetricsStrip';
import ActiveMigrationCard from '@/components/overview/ActiveMigrationCard';
import RecentMigrationsTable from '@/components/overview/RecentMigrationsTable';
import ClusterTopologyBar from '@/components/overview/ClusterTopologyBar';
import PodFleetView from '@/components/overview/PodFleetView';
import { fetchMigrations } from '@/lib/api';
import { MigrationResponse } from '@/lib/types';

export default function OverviewPage() {
  const [migrations, setMigrations] = useState<MigrationResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  useEffect(() => {
    let isMounted = true;
    const loadData = () => {
      fetchMigrations().then((data) => {
        if (isMounted && Array.isArray(data)) {
          setMigrations(data);
          setIsLoading(false);
        }
      });
    };

    loadData();
    const interval = setInterval(loadData, 4000);
    return () => {
      isMounted = false;
      clearInterval(interval);
    };
  }, []);

  const activeMigrations = migrations.filter(
    (m) => m.state === 'BACKFILLING' || m.state === 'READY_FOR_CUTOVER' || m.state === 'READY_CUTOVER' || m.state === 'INITIALIZING'
  );

  return (
    <div className="flex flex-col w-full max-w-6xl mx-auto py-8 gap-8">
      {/* Top Action & Context Header */}
      <header className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 pb-4 border-b border-outline-variant/10">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2 text-primary">
            <span className="w-2 h-2 rounded-full bg-primary animate-pulse"></span>
            <span className="text-[11px] font-mono uppercase tracking-widest text-primary font-medium">
              Cluster Orchestrator · us-east-1
            </span>
          </div>
          <h1 className="text-3xl font-bold tracking-tight text-on-surface">SafeMigrate</h1>
          <p className="text-sm text-on-surface-variant">Zero-downtime PostgreSQL schema migrations</p>
        </div>

        <div className="flex items-center gap-3">
          <a
            href="https://github.com/RavirajSonar40/SafeMigrate#readme"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface-container-low text-on-surface hover:bg-surface-container transition-colors text-xs font-medium shadow-sm border border-outline-variant/20"
          >
            <span className="material-symbols-outlined text-[18px] text-on-surface-variant">menu_book</span>
            <span>Documentation</span>
          </a>
          <Link
            href="/migrations/new"
            className="inline-flex items-center gap-1.5 px-5 py-2 rounded-lg bg-primary text-surface-container-lowest text-xs font-semibold hover:bg-primary-fixed transition-colors shadow-md"
          >
            <span className="material-symbols-outlined text-[18px]">add</span>
            <span>New Migration</span>
          </Link>
        </div>
      </header>

      {/* 4 Elevated KPIs */}
      <MetricsStrip activeCount={activeMigrations.length} />

      {/* Active Migrations Section */}
      <section className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold text-on-surface tracking-tight">Active Migrations</h2>
            <span className="px-2 py-0.5 rounded text-[11px] font-mono bg-primary/10 text-primary uppercase font-medium border border-primary/20">
              {activeMigrations.length} Running
            </span>
          </div>
          <span className="text-xs font-mono text-on-surface-variant flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-primary inline-block animate-ping"></span>
            Live Spring Boot Backend
          </span>
        </div>

        {isLoading ? (
          <div className="p-8 rounded-2xl bg-surface-container-low border border-outline-variant/20 flex items-center justify-center gap-3">
            <span className="material-symbols-outlined text-primary text-[24px] animate-spin">sync</span>
            <span className="text-xs font-mono text-on-surface-variant">Connecting to Spring Boot Cluster Orchestrator...</span>
          </div>
        ) : activeMigrations.length === 0 ? (
          <div className="p-8 rounded-2xl bg-surface-container-low border border-outline-variant/20 flex flex-col items-center text-center gap-3">
            <span className="material-symbols-outlined text-[36px] text-primary">database</span>
            <div className="flex flex-col">
              <span className="text-sm font-bold text-on-surface">Target Database Ready</span>
              <span className="text-xs text-on-surface-variant">Connected to PostgreSQL table &apos;orders&apos; (1,466 records). No active migrations running.</span>
            </div>
            <Link
              href="/migrations/new"
              className="mt-2 inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-surface-container-lowest text-xs font-semibold hover:bg-primary-fixed transition-all"
            >
              <span className="material-symbols-outlined text-[16px]">play_arrow</span>
              <span>Launch First Migration</span>
            </Link>
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

      {/* Recent Migrations Table */}
      <RecentMigrationsTable migrations={migrations} />

      {/* Cluster Operational Baseline Bar */}
      <ClusterTopologyBar />
    </div>
  );
}
