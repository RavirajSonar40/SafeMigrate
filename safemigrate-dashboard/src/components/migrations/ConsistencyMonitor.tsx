'use client';

interface ConsistencyMonitorProps {
  sourceLsn?: string;
  appliedLsnStr?: string;
  appliedLsn?: string;
  divergenceEvents?: number;
  divergenceCount?: number;
  replicationLagBytes?: number;
  appliedInserts?: number;
  appliedUpdates?: number;
  appliedDeletes?: number;
  totalApplied?: number;
  state?: string;
  tableName?: string;
}

export default function ConsistencyMonitor({
  sourceLsn = '0/5A81F21',
  appliedLsnStr,
  appliedLsn,
  divergenceEvents,
  divergenceCount,
  replicationLagBytes = 0,
  appliedInserts = 0,
  appliedUpdates = 0,
  appliedDeletes = 0,
  totalApplied = 0,
  state = 'CATCHING_UP',
  tableName = 'orders'
}: ConsistencyMonitorProps) {
  const actualAppliedLsn = appliedLsn || appliedLsnStr || '0/5A81F21';
  const actualDivergence = divergenceCount ?? divergenceEvents ?? 0;
  const isConsistent = actualDivergence === 0 && replicationLagBytes < 1024;

  return (
    <div className="bg-surface-container border border-outline-variant/30 rounded-2xl p-6 flex flex-col gap-6 shadow-sm">
      {/* Title */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-4 border-b border-outline-variant/15">
        <div>
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-[22px]">sync_saved_locally</span>
            <h3 className="text-base font-bold text-on-surface">Live Consistency & LSN Monitor</h3>
          </div>
          <p className="text-xs text-on-surface-variant mt-0.5">
            Real-time PostgreSQL Log Sequence Number (LSN) synchronization across replication boundary
          </p>
        </div>

        <div className="flex items-center gap-2">
          <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-mono font-semibold ${
            isConsistent
              ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
              : 'bg-amber-500/15 text-amber-400 border border-amber-500/30 animate-pulse'
          }`}>
            <span className={`w-2 h-2 rounded-full ${isConsistent ? 'bg-emerald-400' : 'bg-amber-400'}`}></span>
            <span>{isConsistent ? 'STRICT PARITY · 0 DIVERGENCE' : 'DRAINING CDC LAG'}</span>
          </span>
        </div>
      </div>

      {/* Hero LSN & Divergence Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {/* Source LSN */}
        <div className="p-4 rounded-xl bg-surface-container-high/60 border border-outline-variant/20 flex flex-col gap-1.5">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">
            Source Master LSN
          </span>
          <div className="text-lg font-mono font-bold text-primary flex items-center gap-2">
            <span className="material-symbols-outlined text-[18px] text-primary">storage</span>
            <span>{sourceLsn}</span>
          </div>
          <span className="text-[10px] text-on-surface-variant">Active primary WAL sequence</span>
        </div>

        {/* Applied LSN */}
        <div className="p-4 rounded-xl bg-surface-container-high/60 border border-outline-variant/20 flex flex-col gap-1.5">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">
            Shadow Applied LSN
          </span>
          <div className="text-lg font-mono font-bold text-emerald-400 flex items-center gap-2">
            <span className="material-symbols-outlined text-[18px] text-emerald-400">task_alt</span>
            <span>{actualAppliedLsn}</span>
          </div>
          <span className="text-[10px] text-on-surface-variant">Committed to shadow table heap</span>
        </div>

        {/* Divergence Counter */}
        <div className="p-4 rounded-xl bg-surface-container-high/60 border border-outline-variant/20 flex flex-col gap-1.5">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">
            Event Divergence
          </span>
          <div className="text-lg font-mono font-bold text-on-surface flex items-center gap-2">
            <span className={`material-symbols-outlined text-[18px] ${actualDivergence === 0 ? 'text-emerald-400' : 'text-amber-400'}`}>
              {actualDivergence === 0 ? 'verified' : 'hourglass_top'}
            </span>
            <span>{actualDivergence.toLocaleString()} events</span>
          </div>
          <span className="text-[10px] text-on-surface-variant">
            {replicationLagBytes > 0 ? `${(replicationLagBytes / 1024).toFixed(1)} kB pending drain` : 'Zero backlog in stream'}
          </span>
        </div>
      </div>

      {/* Visual Data Pipeline Flow */}
      <div className="p-4 rounded-xl bg-surface-container-low border border-outline-variant/15 flex flex-col gap-3">
        <span className="text-xs font-semibold text-on-surface">Replication Stream Pipeline Architecture</span>
        
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3 text-center text-xs font-mono">
          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/20 flex flex-col gap-1">
            <span className="text-[10px] text-on-surface-variant">Step 1</span>
            <span className="font-bold text-on-surface">PostgreSQL WAL</span>
            <span className="text-[10px] text-emerald-400">test_decoding</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/20 flex flex-col gap-1">
            <span className="text-[10px] text-on-surface-variant">Step 2</span>
            <span className="font-bold text-on-surface">Logical Slot</span>
            <span className="text-[10px] text-primary">Zero Drop Cursor</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/20 flex flex-col gap-1">
            <span className="text-[10px] text-on-surface-variant">Step 3</span>
            <span className="font-bold text-on-surface">Kafka Buffer</span>
            <span className="text-[10px] text-tertiary">Exactly-Once / At-Least</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/20 flex flex-col gap-1">
            <span className="text-[10px] text-on-surface-variant">Step 4</span>
            <span className="font-bold text-on-surface">Change Applier</span>
            <span className="text-[10px] text-cyan-400">ON CONFLICT DO UPDATE</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/20 flex flex-col gap-1">
            <span className="text-[10px] text-on-surface-variant">Step 5</span>
            <span className="font-bold text-on-surface">Shadow Table</span>
            <span className="text-[10px] text-emerald-400">100% Parity Ready</span>
          </div>
        </div>
      </div>

      {/* Applied Stream Counters */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono">
        <div className="p-3 rounded-xl bg-surface-container-high/40 border border-outline-variant/15 flex flex-col">
          <span className="text-on-surface-variant text-[10px]">INSERTS REPLAYED</span>
          <span className="text-base font-bold text-emerald-400 mt-1">{appliedInserts.toLocaleString()}</span>
        </div>
        <div className="p-3 rounded-xl bg-surface-container-high/40 border border-outline-variant/15 flex flex-col">
          <span className="text-on-surface-variant text-[10px]">UPDATES REPLAYED</span>
          <span className="text-base font-bold text-cyan-400 mt-1">{appliedUpdates.toLocaleString()}</span>
        </div>
        <div className="p-3 rounded-xl bg-surface-container-high/40 border border-outline-variant/15 flex flex-col">
          <span className="text-on-surface-variant text-[10px]">DELETES REPLAYED</span>
          <span className="text-base font-bold text-amber-400 mt-1">{appliedDeletes.toLocaleString()}</span>
        </div>
        <div className="p-3 rounded-xl bg-surface-container-high/40 border border-outline-variant/15 flex flex-col">
          <span className="text-on-surface-variant text-[10px]">TOTAL DUAL-WRITES</span>
          <span className="text-base font-bold text-on-surface mt-1">{totalApplied.toLocaleString()}</span>
        </div>
      </div>
    </div>
  );
}
