import { 
  MigrationResponse, 
  ReconciliationReport, 
  WorkerStatusDto, 
  ChaosAction, 
  ChaosInjectionResponse, 
  RecoveryResponse,
  DependencyGraphDto 
} from './types';

const DEMO_STORAGE_KEY = 'safemigrate_demo_mode';
const DEMO_STATE_KEY = 'safemigrate_demo_state';

export interface DemoState {
  enabled: boolean;
  rowsBackfilled: number;
  totalSourceRows: number;
  state: string;
  sourceLsn: string;
  appliedLsnStr: string;
  activeChaosAction: string | null;
  resilienceEvents: string[];
  cutoverDurationMs: number | null;
  reconciliationReport: ReconciliationReport | null;
  lastUpdated: number;
}

const DEFAULT_DEMO_STATE: DemoState = {
  enabled: false,
  rowsBackfilled: 7708000,
  totalSourceRows: 8200000,
  state: 'CATCHING_UP',
  sourceLsn: '0/5A81F21',
  appliedLsnStr: '0/5A81F21',
  activeChaosAction: null,
  resilienceEvents: [
    '2026-09-12 05:00:01 UTC - Preflight check passed (0 blockers, PK: id verified)',
    '2026-09-12 05:00:03 UTC - Shadow table orders__shadow provisioned with NUMERIC(14,4)',
    '2026-09-12 05:00:04 UTC - CDC replication slot slot_orders_demo active (0 lag)',
    '2026-09-12 05:00:05 UTC - Checkpointed backfill started (50,000 rows/batch)',
  ],
  cutoverDurationMs: null,
  reconciliationReport: null,
  lastUpdated: Date.now()
};

export function isDemoMode(): boolean {
  if (typeof window === 'undefined') return false;
  return localStorage.getItem(DEMO_STORAGE_KEY) === 'true';
}

export function setDemoMode(enabled: boolean): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(DEMO_STORAGE_KEY, enabled ? 'true' : 'false');
  if (enabled && !localStorage.getItem(DEMO_STATE_KEY)) {
    saveDemoState({ ...DEFAULT_DEMO_STATE, enabled: true });
  }
}

export function getDemoState(): DemoState {
  if (typeof window === 'undefined') return DEFAULT_DEMO_STATE;
  try {
    const raw = localStorage.getItem(DEMO_STATE_KEY);
    if (!raw) return DEFAULT_DEMO_STATE;
    return JSON.parse(raw);
  } catch {
    return DEFAULT_DEMO_STATE;
  }
}

export function saveDemoState(state: DemoState): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(DEMO_STATE_KEY, JSON.stringify(state));
}

export function resetDemoState(): void {
  saveDemoState({ ...DEFAULT_DEMO_STATE, enabled: true, lastUpdated: Date.now() });
}

export function getDemoMigration(): MigrationResponse {
  const ds = getDemoState();
  
  // Progress simulation if running
  if (ds.state === 'BACKFILLING' || ds.state === 'CATCHING_UP') {
    const elapsedSec = Math.min(10, Math.floor((Date.now() - ds.lastUpdated) / 1000));
    if (elapsedSec > 0 && ds.rowsBackfilled < ds.totalSourceRows) {
      ds.rowsBackfilled = Math.min(ds.totalSourceRows, ds.rowsBackfilled + elapsedSec * 8400);
      ds.lastUpdated = Date.now();
      if (ds.rowsBackfilled >= ds.totalSourceRows && ds.state === 'BACKFILLING') {
        ds.state = 'READY_CUTOVER';
      }
      saveDemoState(ds);
    }
  }

  const pct = Math.round((ds.rowsBackfilled / ds.totalSourceRows) * 1000) / 10;

  return {
    id: 'demo_mig_orders_8m',
    tableName: 'orders',
    shadowTableName: 'orders__shadow',
    oldTableName: 'orders__old',
    slotName: 'slot_orders_production_demo',
    kafkaTopic: 'safemigrate.wal.orders',
    ddlStatement: 'ALTER TABLE _sm_shadow_orders ALTER COLUMN amount TYPE NUMERIC(14,4); ALTER TABLE _sm_shadow_orders ADD COLUMN priority_score INT DEFAULT 100 NOT NULL;',
    state: ds.state as any,
    totalSourceRows: ds.totalSourceRows,
    rowsBackfilled: ds.rowsBackfilled,
    progressPercentage: pct,
    replicationLagBytes: ds.state === 'COMPLETED' ? 0 : 84,
    lastAppliedLsn: 94902177,
    appliedInserts: 124800,
    appliedUpdates: 43900,
    appliedDeletes: 1210,
    totalApplied: 169910,
    approved: ds.state === 'COMPLETED' || ds.state === 'READY_CUTOVER',
    approvedBy: 'lead-dba@safemigrate.io',
    approvedAt: '2026-09-12T05:20:00Z',
    cutoverDurationMs: ds.cutoverDurationMs ?? undefined,
    createdAt: '2026-09-12T05:00:00Z',
    startedAt: '2026-09-12T05:00:05Z',
    completedAt: ds.state === 'COMPLETED' ? new Date().toISOString() : undefined,
    database: 'production-aurora-cluster',
    databaseId: 'production-aurora-cluster',
    sourceLsn: ds.sourceLsn,
    appliedLsnStr: ds.appliedLsnStr,
    divergenceEvents: 0,
    lastCheckpointPk: 7708000,
    activeChaosAction: ds.activeChaosAction ?? undefined,
    resilienceEvents: ds.resilienceEvents,
    reconciliationReport: ds.reconciliationReport ?? undefined,
  };
}

export function injectDemoChaos(action: ChaosAction): ChaosInjectionResponse {
  const ds = getDemoState();
  ds.activeChaosAction = action;
  ds.state = 'PAUSED';
  ds.resilienceEvents.push(
    `${new Date().toISOString().substring(11, 19)} UTC - Resilience Incident: ${action}. Checkpoint saved at PK ${ds.rowsBackfilled.toLocaleString()}`
  );
  saveDemoState(ds);

  return {
    migrationId: 'demo_mig_orders_8m',
    action,
    success: true,
    message: `Injected chaos ${action}. Component halted safely.`,
    affectedComponent: action.replace('KILL_', ''),
    previousState: 'CATCHING_UP',
    currentState: 'PAUSED',
    checkpointPk: ds.rowsBackfilled,
    injectedAt: new Date().toISOString()
  };
}

export function recoverDemoMigration(): RecoveryResponse {
  const ds = getDemoState();
  const resumedPk = ds.rowsBackfilled;
  ds.activeChaosAction = null;
  ds.state = 'READY_CUTOVER';
  ds.rowsBackfilled = ds.totalSourceRows;
  ds.resilienceEvents.push(
    `${new Date().toISOString().substring(11, 19)} UTC - Self-Healing Engine: Worker restarted from checkpoint PK ${resumedPk.toLocaleString()}. Replay active. 0 events lost, 0 duplicates.`
  );
  saveDemoState(ds);

  return {
    migrationId: 'demo_mig_orders_8m',
    recovered: true,
    message: `Resumed from checkpoint PK ${resumedPk.toLocaleString()} with 0 events lost and 0 duplicates.`,
    restoredComponent: 'ALL_WORKERS',
    resumedFromPk: resumedPk,
    eventsLost: 0,
    duplicateEvents: 0,
    currentState: 'READY_CUTOVER',
    recoveredAt: new Date().toISOString()
  };
}

export function cutoverDemoMigration(): MigrationResponse {
  const ds = getDemoState();
  ds.state = 'COMPLETED';
  ds.cutoverDurationMs = 18;
  ds.reconciliationReport = {
    tableName: 'orders',
    comparedTable: 'orders__old',
    matched: true,
    sourceRowCount: 8200000,
    targetRowCount: 8200000,
    sourceChecksum: -8920194817492193821,
    targetChecksum: -8920194817492193821,
    comparedColumns: ['id', 'user_id', 'merchant_id', 'amount', 'currency', 'status', 'created_at', 'updated_at', 'priority_score'],
    discrepancyCount: 0,
    sampleDiscrepancies: [],
    executionTimeMs: 412,
    verifiedAt: new Date().toISOString()
  };
  ds.resilienceEvents.push(
    `${new Date().toISOString().substring(11, 19)} UTC - Atomic Cutover completed in 18ms. 100% data parity certified by 64-bit aggregate XOR checksum.`
  );
  saveDemoState(ds);
  return getDemoMigration();
}

export function verifyDemoMigration(): ReconciliationReport {
  const ds = getDemoState();
  const rep: ReconciliationReport = {
    tableName: 'orders',
    comparedTable: 'orders__shadow',
    matched: true,
    sourceRowCount: ds.totalSourceRows,
    targetRowCount: ds.totalSourceRows,
    sourceChecksum: 'sha256:e8f9a2b71c0459d1a8904f6e',
    targetChecksum: 'sha256:e8f9a2b71c0459d1a8904f6e',
    comparedColumns: ['id', 'user_id', 'amount_cents', 'status', 'priority_score', 'created_at'],
    discrepancyCount: 0,
    sampleDiscrepancies: [],
    executionTimeMs: 384,
    verifiedAt: new Date().toISOString(),
    id: 'rep_demo_' + Date.now(),
    migrationId: 'demo-orders-v2',
    shadowRowCount: ds.totalSourceRows,
    sourceHash: 'sha256:e8f9a2b71c0459d1a8904f6e',
    shadowHash: 'sha256:e8f9a2b71c0459d1a8904f6e',
    mismatchedRowsCount: 0,
    divergentKeys: [],
    status: 'VERIFIED_PARITY',
    createdAt: new Date().toISOString()
  };
  ds.reconciliationReport = rep;
  saveDemoState(ds);
  return rep;
}

export function getDemoWorkers(): WorkerStatusDto[] {
  const ds = getDemoState();
  const isHealthy = ds.state !== 'PAUSED';

  return [
    {
      id: 'demo-wal-reader',
      name: 'PostgreSQL WAL Reader',
      component: 'WAL_READER',
      status: isHealthy ? 'HEALTHY' : 'INTERRUPTED',
      lsn: ds.sourceLsn,
      throughputEventsPerSec: isHealthy ? 12481 : 0,
      lagBytes: isHealthy ? 84 : 14200,
      lagMs: isHealthy ? 12 : 210,
      message: isHealthy ? 'Consuming logical replication slot: slot_orders_production_demo' : 'Worker halted by Chaos Injection',
      lastHeartbeat: new Date().toISOString()
    },
    {
      id: 'demo-backfill-worker',
      name: 'Chunked Backfill Worker',
      component: 'BACKFILL_WORKER',
      status: ds.state === 'COMPLETED' ? 'COMPLETED' : (isHealthy ? 'RUNNING' : 'PAUSED'),
      currentPk: ds.rowsBackfilled,
      throughputEventsPerSec: isHealthy ? 26500 : 0,
      lagBytes: 0,
      lagMs: 0,
      message: `Copied ${ds.rowsBackfilled.toLocaleString()} / 8,200,000 rows (Chunk: 50,000)`,
      lastHeartbeat: new Date().toISOString()
    },
    {
      id: 'demo-change-applier',
      name: 'Transactional Change Applier',
      component: 'CHANGE_APPLIER',
      status: ds.state === 'COMPLETED' ? 'IDLE' : (isHealthy ? 'HEALTHY' : 'PAUSED'),
      lsn: ds.appliedLsnStr,
      offset: 9182821,
      throughputEventsPerSec: isHealthy ? 2400 : 0,
      lagBytes: isHealthy ? 0 : 5400,
      lagMs: isHealthy ? 4 : 85,
      message: 'Kafka consumer partition: 4. Applying dual-writes in transaction batches.',
      lastHeartbeat: new Date().toISOString()
    },
    {
      id: 'demo-cutover-coordinator',
      name: 'Atomic Cutover Coordinator',
      component: 'CUTOVER_COORDINATOR',
      status: ds.state === 'COMPLETED' ? 'COMPLETED' : (ds.state === 'READY_CUTOVER' ? 'HEALTHY' : 'IDLE'),
      throughputEventsPerSec: 0,
      lagBytes: 0,
      lagMs: 0,
      message: ds.state === 'COMPLETED' ? 'Table rename committed in 18 ms' : 'Armed with 2,000ms lock timeout guard',
      lastHeartbeat: new Date().toISOString()
    }
  ];
}

export function getDemoDependencies(): DependencyGraphDto {
  return {
    tableName: 'orders',
    totalDependencies: 4,
    riskLevel: 'LOW',
    warnings: [
      '2 incoming foreign keys reference orders.id (order_items, payment_transactions)',
      '1 active trigger detected: orders_audit_sync'
    ],
    incomingForeignKeys: [
      {
        constraintName: 'fk_order_items_order_id',
        sourceTable: 'order_items',
        sourceColumn: 'order_id',
        targetTable: 'orders',
        targetColumn: 'id',
        onUpdate: 'CASCADE',
        onDelete: 'CASCADE'
      },
      {
        constraintName: 'fk_payment_transactions_order_id',
        sourceTable: 'payment_transactions',
        sourceColumn: 'order_id',
        targetTable: 'orders',
        targetColumn: 'id',
        onUpdate: 'RESTRICT',
        onDelete: 'RESTRICT'
      }
    ],
    outgoingForeignKeys: [
      {
        constraintName: 'fk_orders_user_id',
        sourceTable: 'orders',
        sourceColumn: 'user_id',
        targetTable: 'users',
        targetColumn: 'id',
        onUpdate: 'CASCADE',
        onDelete: 'RESTRICT'
      }
    ],
    triggers: [
      {
        triggerName: 'orders_audit_sync',
        event: 'UPDATE OR INSERT',
        timing: 'AFTER',
        actionStatement: 'EXECUTE FUNCTION log_order_mutation()'
      }
    ]
  };
}
