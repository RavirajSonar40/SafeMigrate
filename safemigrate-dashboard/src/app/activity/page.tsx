'use client';

import React, { useState, useMemo } from 'react';
import Link from 'next/link';

interface ActivityEvent {
  id: string;
  time: string;
  title: string;
  category: 'lifecycle' | 'preflight' | 'resilience' | 'reconciliation' | 'cutover';
  status: 'info' | 'success' | 'warning' | 'chaos';
  operator: string;
  migrationId: string;
  summary: string;
  details: {
    lsn?: string;
    durationMs?: number;
    rowsAffected?: string;
    payload?: string;
    tags: string[];
  };
}

const INITIAL_EVENTS: ActivityEvent[] = [
  {
    id: 'evt-08',
    time: '14:17',
    title: 'Cutover completed',
    category: 'cutover',
    status: 'success',
    operator: '@RavirajSonar40 (Dual Sign-off)',
    migrationId: 'mig_orders_add_priority',
    summary: 'Atomic zero-downtime swap executed. Shadow table promoted to production in 28ms.',
    details: {
      lsn: '0/18F4290',
      durationMs: 28,
      rowsAffected: '8,200,000 rows',
      payload: 'ALTER TABLE orders RENAME TO orders__old; ALTER TABLE orders__shadow RENAME TO orders;',
      tags: ['ATOMIC_RENAME', 'ZERO_DOWNTIME', 'CUTOVER_PASSED']
    }
  },
  {
    id: 'evt-07',
    time: '14:17',
    title: 'Reconciliation passed',
    category: 'reconciliation',
    status: 'success',
    operator: 'Verification Engine (Autonomous)',
    migrationId: 'mig_orders_add_priority',
    summary: 'Dual MD5 hash verification certified 100% parity across source and shadow tables.',
    details: {
      durationMs: 1420,
      rowsAffected: '8,200,000 / 8,200,000 rows',
      payload: 'PARITY_CHECK: Source checksum = 4f9b208c, Shadow checksum = 4f9b208c. Mismatch: 0.',
      tags: ['PARITY_100%', 'MD5_MATCH', 'ZERO_DISCREPANCY']
    }
  },
  {
    id: 'evt-06',
    time: '14:12',
    title: 'Worker resumed',
    category: 'resilience',
    status: 'success',
    operator: 'Autonomous Supervisor',
    migrationId: 'mig_orders_add_priority',
    summary: 'Worker #2 re-entered backfill pool. CDC streaming pipeline caught up to head in 240ms.',
    details: {
      lsn: '0/16B2580',
      durationMs: 240,
      rowsAffected: 'Catch-up: 420 events',
      payload: 'Worker thread re-allocated via Loom virtual executor. CDC lag reduced from 340ms to 28ms.',
      tags: ['STREAM_CATCHUP', 'RESUMED', 'ZERO_DATA_LOSS']
    }
  },
  {
    id: 'evt-05',
    time: '14:11',
    title: 'Checkpoint restored',
    category: 'resilience',
    status: 'warning',
    operator: 'Distributed Lease Manager',
    migrationId: 'mig_orders_add_priority',
    summary: 'Redis distributed checkpoint recovered at LSN 0/16B2540. Replay lease re-acquired.',
    details: {
      lsn: '0/16B2540',
      durationMs: 45,
      rowsAffected: 'Chunk #41 (offset 4,100,000)',
      payload: 'CHECKPOINT_RESTORED: Last safe write committed. Exactly-once idempotency preserved.',
      tags: ['CHECKPOINT_RESTORE', 'EXACTLY_ONCE', 'REDIS_LEASE']
    }
  },
  {
    id: 'evt-04',
    time: '14:11',
    title: 'Worker interrupted',
    category: 'resilience',
    status: 'chaos',
    operator: 'Chaos Simulation (Injected)',
    migrationId: 'mig_orders_add_priority',
    summary: 'Worker safemigrate_worker_2 terminated (SIGKILL). Distributed lock lease timeout triggered.',
    details: {
      lsn: '0/16B2540',
      durationMs: 0,
      rowsAffected: '0 data corruption',
      payload: 'FAULT_INJECTION: SIGKILL sent to backfill worker thread #2. Uncommitted chunk rolled back.',
      tags: ['CHAOS_MODE', 'FAILURE_INJECTED', 'WORKER_KILLED']
    }
  },
  {
    id: 'evt-03',
    time: '14:04',
    title: 'Backfill started',
    category: 'lifecycle',
    status: 'info',
    operator: 'Loom Orchestrator',
    migrationId: 'mig_orders_add_priority',
    summary: 'Dual-table replication started. 8 virtual threads chunking 8,200,000 rows with 20ms pacing.',
    details: {
      lsn: '0/14A0020',
      durationMs: 0,
      rowsAffected: '8,200,000 rows queued',
      payload: 'CHUNK_SIZE=5000; WORKERS=8; THROTTLE_MS=20; ADAPTIVE_PACING=ENABLED',
      tags: ['BACKFILL_START', 'VIRTUAL_THREADS', 'CHUNKING']
    }
  },
  {
    id: 'evt-02',
    time: '14:03',
    title: 'Preflight passed',
    category: 'preflight',
    status: 'success',
    operator: 'Safety Gate Engine',
    migrationId: 'mig_orders_add_priority',
    summary: '6 of 6 safety gates certified. Lock contention: 0ms. Disk headroom: 24.8 GB. WAL slot healthy.',
    details: {
      durationMs: 380,
      payload: 'CHECKS: [PK_EXISTS: OK, DISK_HEADROOM: OK, REPLICATION_SLOT: OK, LOCK_TIMEOUT: OK, SCHEMA_SAFE: OK]',
      tags: ['PREFLIGHT_PASS', 'ZERO_LOCK', 'SLOT_READY']
    }
  },
  {
    id: 'evt-01',
    time: '14:02',
    title: 'Migration created',
    category: 'lifecycle',
    status: 'info',
    operator: '@RavirajSonar40',
    migrationId: 'mig_orders_add_priority',
    summary: 'Target table public.orders registered with shadow table orders__shadow and safe DDL plan.',
    details: {
      payload: 'ALTER TABLE orders ADD COLUMN priority INT DEFAULT 0;',
      tags: ['MIGRATION_INIT', 'PLAN_REGISTERED', 'ONLINE_DDL']
    }
  }
];

export default function ActivityPage() {
  const [events, setEvents] = useState<ActivityEvent[]>(INITIAL_EVENTS);
  const [selectedCategory, setSelectedCategory] = useState<string>('all');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [expandedEventId, setExpandedEventId] = useState<string | null>(null);
  const [isLiveStream, setIsLiveStream] = useState<boolean>(true);

  const filteredEvents = useMemo(() => {
    return events.filter((evt) => {
      const matchesCat = selectedCategory === 'all' || evt.category === selectedCategory;
      const matchesSearch =
        evt.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        evt.summary.toLowerCase().includes(searchQuery.toLowerCase()) ||
        evt.time.includes(searchQuery) ||
        evt.operator.toLowerCase().includes(searchQuery.toLowerCase()) ||
        evt.details.tags.some((t) => t.toLowerCase().includes(searchQuery.toLowerCase()));
      return matchesCat && matchesSearch;
    });
  }, [events, selectedCategory, searchQuery]);

  const handleSimulateEvent = () => {
    const now = new Date();
    const timeStr = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    const newEvt: ActivityEvent = {
      id: `evt-${Date.now()}`,
      time: timeStr,
      title: 'Periodic checkpoint committed',
      category: 'lifecycle',
      status: 'info',
      operator: 'Background Flusher',
      migrationId: 'mig_orders_add_priority',
      summary: `Safe LSN checkpoint committed at ${timeStr}. Buffer flush verified with 0ms lag.`,
      details: {
        lsn: `0/${Math.floor(Math.random() * 900000 + 100000).toString(16).toUpperCase()}`,
        durationMs: Math.floor(Math.random() * 30 + 10),
        rowsAffected: '1,420 CDC rows',
        payload: 'COMMIT_CHECKPOINT: state flushed to Redis cluster & local WAL tracker.',
        tags: ['LIVE_SIMULATION', 'CHECKPOINT', 'AUTONOMOUS']
      }
    };
    setEvents((prev) => [newEvt, ...prev]);
  };

  const getStatusBadge = (status: ActivityEvent['status']) => {
    switch (status) {
      case 'success':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
            <span className="material-symbols-outlined text-[12px]">check_circle</span>
            PASSED
          </span>
        );
      case 'warning':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-amber-500/10 text-amber-400 border border-amber-500/30">
            <span className="material-symbols-outlined text-[12px]">warning</span>
            RECOVERY
          </span>
        );
      case 'chaos':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-rose-500/10 text-rose-400 border border-rose-500/30 animate-pulse">
            <span className="material-symbols-outlined text-[12px]">bolt</span>
            FAULT INJECTED
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-cyan-500/10 text-cyan-400 border border-cyan-500/30">
            <span className="material-symbols-outlined text-[12px]">info</span>
            LIFECYCLE
          </span>
        );
    }
  };

  const getIcon = (status: ActivityEvent['status']) => {
    switch (status) {
      case 'success':
        return { icon: 'verified', color: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/30' };
      case 'warning':
        return { icon: 'restart_alt', color: 'text-amber-400 bg-amber-500/10 border-amber-500/30' };
      case 'chaos':
        return { icon: 'error_outline', color: 'text-rose-400 bg-rose-500/10 border-rose-500/30' };
      default:
        return { icon: 'play_arrow', color: 'text-cyan-400 bg-cyan-500/10 border-cyan-500/30' };
    }
  };

  const handleExportJSON = () => {
    const dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(events, null, 2));
    const downloadAnchor = document.createElement('a');
    downloadAnchor.setAttribute('href', dataStr);
    downloadAnchor.setAttribute('download', `safemigrate_audit_log_${new Date().toISOString().slice(0, 10)}.json`);
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
  };

  return (
    <div className="flex-1 min-h-screen bg-surface-container-lowest text-on-surface p-6 md:p-8 space-y-6">
      {/* Top Breadcrumb & Actions */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-outline-variant/20 pb-5">
        <div>
          <div className="flex items-center gap-2 text-xs font-mono text-outline mb-1">
            <Link href="/overview" className="hover:text-primary transition-colors">SafeMigrate</Link>
            <span>/</span>
            <span className="text-on-surface">Audit &amp; Activity Experience</span>
          </div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-on-surface">Activity Timeline &amp; State Ledger</h1>
            <div className="flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs font-mono">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping inline-block"></span>
              <span>{isLiveStream ? 'Live Stream Active' : 'Stream Paused'}</span>
            </div>
          </div>
          <p className="text-xs text-on-surface-variant mt-1">
            Immutable chronicle of state transitions, dual sign-off approvals, chaos interruptions, and checkpoint recoveries.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={() => setIsLiveStream(!isLiveStream)}
            className={`px-3 py-1.5 rounded-lg border text-xs font-mono font-medium transition-colors cursor-pointer flex items-center gap-1.5 ${
              isLiveStream
                ? 'bg-surface-container-low border-outline-variant/30 text-on-surface hover:bg-surface-container'
                : 'bg-amber-500/10 border-amber-500/30 text-amber-300 hover:bg-amber-500/20'
            }`}
          >
            <span className="material-symbols-outlined text-[16px]">{isLiveStream ? 'pause' : 'play_arrow'}</span>
            <span>{isLiveStream ? 'Pause Stream' : 'Resume'}</span>
          </button>

          <button
            onClick={handleSimulateEvent}
            className="px-3 py-1.5 rounded-lg bg-surface-container-low border border-primary/30 text-primary hover:bg-primary/10 text-xs font-mono font-bold transition-colors cursor-pointer flex items-center gap-1.5"
            title="Append simulated real-time checkpoint event"
          >
            <span className="material-symbols-outlined text-[16px]">add_circle</span>
            <span>Simulate Event</span>
          </button>

          <button
            onClick={handleExportJSON}
            className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high border border-outline-variant/30 text-on-surface text-xs font-medium transition-colors cursor-pointer flex items-center gap-1.5"
          >
            <span className="material-symbols-outlined text-[16px]">download</span>
            <span>Export Ledger</span>
          </button>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 bg-surface-container-low/60 p-3 rounded-xl border border-outline-variant/20">
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0">
          {[
            { id: 'all', label: 'All Events' },
            { id: 'lifecycle', label: 'Lifecycle' },
            { id: 'preflight', label: 'Pre-flight' },
            { id: 'resilience', label: 'Chaos & Recovery' },
            { id: 'reconciliation', label: 'Parity Engine' },
            { id: 'cutover', label: 'Cutover' }
          ].map((cat) => (
            <button
              key={cat.id}
              onClick={() => setSelectedCategory(cat.id)}
              className={`px-3 py-1 rounded-lg text-xs font-mono font-medium transition-all whitespace-nowrap cursor-pointer ${
                selectedCategory === cat.id
                  ? 'bg-primary text-surface-container-lowest font-bold shadow-sm'
                  : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
              }`}
            >
              {cat.label}
            </button>
          ))}
        </div>

        <div className="relative min-w-[240px]">
          <span className="material-symbols-outlined absolute left-2.5 top-2 text-[18px] text-outline">search</span>
          <input
            type="text"
            placeholder="Search timeline (e.g. checkpoint, LSN, 14:11)..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-8 pr-3 py-1.5 rounded-lg bg-surface-container-lowest border border-outline-variant/30 text-xs text-on-surface placeholder:text-outline focus:outline-none focus:border-primary font-mono"
          />
        </div>
      </div>

      {/* Summary Stat Strips */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">Total Ledger Records</span>
          <span className="text-xl font-bold font-mono text-on-surface mt-1">{events.length}</span>
          <span className="text-[10px] text-emerald-400 font-mono mt-0.5">● Append-only immutable log</span>
        </div>
        <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">Fault Recoveries</span>
          <span className="text-xl font-bold font-mono text-amber-400 mt-1">2 Events</span>
          <span className="text-[10px] text-on-surface-variant font-mono mt-0.5">Zero uncommitted loss</span>
        </div>
        <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">Parity Certification</span>
          <span className="text-xl font-bold font-mono text-emerald-400 mt-1">100.0% Match</span>
          <span className="text-[10px] text-on-surface-variant font-mono mt-0.5">8.2M / 8.2M rows verified</span>
        </div>
        <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">Cutover Duration</span>
          <span className="text-xl font-bold font-mono text-primary mt-1">28 ms</span>
          <span className="text-[10px] text-on-surface-variant font-mono mt-0.5">Lock wait &lt; 5ms</span>
        </div>
      </div>

      {/* Centered Polished Activity Timeline */}
      <div className="rounded-2xl bg-surface-container-low/40 border border-outline-variant/20 p-6">
        <div className="flex items-center justify-between pb-4 mb-6 border-b border-outline-variant/15">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-[20px]">timeline</span>
            <h2 className="text-sm font-bold text-on-surface uppercase tracking-wider font-mono">
              Chronological Execution Trace (orders &rarr; orders__shadow)
            </h2>
          </div>
          <span className="text-xs font-mono text-outline">
            Showing {filteredEvents.length} transition point{filteredEvents.length === 1 ? '' : 's'}
          </span>
        </div>

        {filteredEvents.length === 0 ? (
          <div className="py-12 text-center text-on-surface-variant text-sm font-mono">
            No events match the active search filter.
          </div>
        ) : (
          <div className="relative pl-6 sm:pl-10 space-y-6 before:absolute before:left-3 sm:before:left-5 before:top-3 before:bottom-3 before:w-[2px] before:bg-outline-variant/30">
            {filteredEvents.map((evt) => {
              const iconMeta = getIcon(evt.status);
              const isExpanded = expandedEventId === evt.id;

              return (
                <div key={evt.id} className="relative group">
                  {/* Timeline Pin Node */}
                  <div
                    className={`absolute -left-6 sm:-left-10 top-1.5 w-6 h-6 rounded-full border flex items-center justify-center shadow-sm z-10 transition-transform group-hover:scale-110 ${iconMeta.color}`}
                  >
                    <span className="material-symbols-outlined text-[14px]">{iconMeta.icon}</span>
                  </div>

                  {/* Card Body */}
                  <div
                    onClick={() => setExpandedEventId(isExpanded ? null : evt.id)}
                    className={`p-4 rounded-xl border transition-all cursor-pointer ${
                      isExpanded
                        ? 'bg-surface-container border-primary/40 shadow-lg'
                        : 'bg-surface-container-low hover:bg-surface-container/80 border-outline-variant/20 hover:border-outline-variant/40'
                    }`}
                  >
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                      <div className="flex items-center gap-3">
                        <span className="font-mono text-sm font-bold text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                          {evt.time}
                        </span>
                        <h3 className="text-sm font-bold text-on-surface tracking-tight flex items-center gap-2">
                          {evt.title}
                        </h3>
                        {getStatusBadge(evt.status)}
                      </div>

                      <div className="flex items-center gap-2 text-xs font-mono text-on-surface-variant">
                        <span>{evt.operator}</span>
                        <span className="text-outline">·</span>
                        <span className="text-outline text-[11px]">{evt.migrationId}</span>
                        <span className="material-symbols-outlined text-[16px] text-outline ml-1">
                          {isExpanded ? 'expand_less' : 'expand_more'}
                        </span>
                      </div>
                    </div>

                    <p className="text-xs text-on-surface-variant mt-2 leading-relaxed">
                      {evt.summary}
                    </p>

                    {/* Tag badges */}
                    <div className="flex flex-wrap items-center gap-1.5 mt-2.5">
                      {evt.details.tags.map((tag) => (
                        <span
                          key={tag}
                          className="text-[10px] font-mono px-2 py-0.5 rounded bg-surface-container-highest text-outline border border-outline-variant/20"
                        >
                          {tag}
                        </span>
                      ))}
                      {evt.details.durationMs !== undefined && (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-surface-container-highest text-primary border border-primary/20">
                          ⏱ {evt.details.durationMs}ms
                        </span>
                      )}
                      {evt.details.lsn && (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-surface-container-highest text-amber-300/90 border border-amber-500/20">
                          LSN: {evt.details.lsn}
                        </span>
                      )}
                    </div>

                    {/* Expanded Technical Payload */}
                    {isExpanded && (
                      <div className="mt-4 pt-3 border-t border-outline-variant/15 space-y-2.5 animate-fade-in text-xs font-mono">
                        {evt.details.rowsAffected && (
                          <div className="flex items-center gap-2 text-on-surface-variant">
                            <span className="text-outline">Scope / Volume:</span>
                            <span className="text-on-surface font-semibold">{evt.details.rowsAffected}</span>
                          </div>
                        )}
                        {evt.details.payload && (
                          <div className="p-3 rounded-lg bg-surface-container-lowest border border-outline-variant/25 text-primary text-[11px] leading-relaxed break-all">
                            <span className="text-outline block text-[10px] uppercase font-bold mb-1">Raw Execution Log / DDL</span>
                            {evt.details.payload}
                          </div>
                        )}
                        <div className="flex items-center justify-between text-[11px] text-outline pt-1">
                          <span>Event UUID: {evt.id}</span>
                          <span className="text-emerald-400">Validated on Redis Consensus</span>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Bottom Quick Navigation */}
      <div className="flex flex-wrap items-center justify-between gap-4 p-4 rounded-xl bg-surface-container-low border border-outline-variant/20 text-xs">
        <div className="flex items-center gap-2 text-on-surface-variant">
          <span className="material-symbols-outlined text-primary text-[18px]">verified_user</span>
          <span>Dual-signoff approval trail meets enterprise SOC2 / HIPAA logical partition compliance standards.</span>
        </div>
        <div className="flex items-center gap-3">
          <Link
            href="/overview"
            className="text-primary hover:underline font-mono flex items-center gap-1"
          >
            <span>Back to Executive Overview</span>
            <span className="material-symbols-outlined text-[14px]">arrow_forward</span>
          </Link>
        </div>
      </div>
    </div>
  );
}
