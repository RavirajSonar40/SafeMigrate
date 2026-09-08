import Link from 'next/link';
import MetricsStrip from '@/components/overview/MetricsStrip';
import ActiveMigrationCard from '@/components/overview/ActiveMigrationCard';
import RecentMigrationsTable from '@/components/overview/RecentMigrationsTable';
import ClusterTopologyBar from '@/components/overview/ClusterTopologyBar';
import PodFleetView from '@/components/overview/PodFleetView';
import { fetchMigrations } from '@/lib/api';

export const dynamic = 'force-dynamic';

export default async function OverviewPage() {
  const migrations = await fetchMigrations();

  const activeMigrations = migrations.filter(
    (m) => m.state === 'BACKFILLING' || m.state === 'READY_FOR_CUTOVER' || m.state === 'INITIALIZING'
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

      {/* Docker Desktop & Pod Fleet Live Monitoring */}
      <PodFleetView />

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
            Auto-refresh 5s
          </span>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {activeMigrations.map((migration) => (
            <ActiveMigrationCard key={migration.id} migration={migration} />
          ))}
        </div>
      </section>

      {/* Recent Migrations Table */}
      <RecentMigrationsTable migrations={migrations} />

      {/* Cluster Operational Baseline Bar */}
      <ClusterTopologyBar />
    </div>
  );
}
