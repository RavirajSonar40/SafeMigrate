import { MigrationResponse, PreflightReport } from './types';

export const MOCK_PREFLIGHT_REPORT: PreflightReport = {
  tableName: 'public.orders',
  passed: true,
  issues: [
    {
      severity: 'WARNING',
      code: 'HIGH_VOLUME_TABLE',
      message: 'Table contains >5M rows. Adaptive throttler will monitor replica lag dynamically.',
      remediation: 'Batch size will auto-scale between 1,000 and 10,000 tuples.'
    }
  ],
  estimatedRows: 8200000,
  tableSizeBytes: 1524687257,
  activeLocksDetected: 0
};

export const MOCK_MIGRATIONS: MigrationResponse[] = [
  {
    id: 'SM-1042',
    tableName: 'public.orders',
    shadowTableName: 'public._sm_shadow_orders_1042',
    ddlStatement: 'ALTER TABLE orders ADD COLUMN priority_score INT DEFAULT 0 NOT NULL;',
    state: 'BACKFILLING',
    sourceRowCount: 8200000,
    rowsBackfilled: 6284000,
    replicationLagBytes: 142000,
    createdAt: '2026-09-08T14:28:10Z',
    startedAt: '2026-09-08T14:28:15Z',
    initiatedBy: 'alex.v@infra.internal',
    database: 'production-db-us-east',
    preflightReport: MOCK_PREFLIGHT_REPORT
  },
  {
    id: 'SM-1041',
    tableName: 'public.charges',
    shadowTableName: 'public._sm_shadow_charges_1041',
    ddlStatement: 'ALTER TABLE charges ADD COLUMN external_id UUID NOT NULL UNIQUE;',
    state: 'READY_FOR_CUTOVER',
    sourceRowCount: 4500000,
    rowsBackfilled: 4500000,
    replicationLagBytes: 0,
    createdAt: '2026-09-08T13:40:00Z',
    startedAt: '2026-09-08T13:40:05Z',
    initiatedBy: 'sarah.k@finance.internal',
    database: 'billing-db-cluster',
    preflightReport: {
      tableName: 'public.charges',
      passed: true,
      issues: [],
      estimatedRows: 4500000,
      tableSizeBytes: 984500000,
      activeLocksDetected: 0
    }
  },
  {
    id: 'SM-1040',
    tableName: 'public.events_daily',
    shadowTableName: 'public._sm_shadow_events_daily_1040',
    ddlStatement: 'CREATE INDEX CONCURRENTLY idx_evt_ts ON events_daily(timestamp DESC);',
    state: 'COMPLETED',
    sourceRowCount: 24000000,
    rowsBackfilled: 24000000,
    replicationLagBytes: 0,
    createdAt: '2026-09-08T11:00:00Z',
    startedAt: '2026-09-08T11:00:10Z',
    completedAt: '2026-09-08T11:18:52Z',
    initiatedBy: 'alex.d@company.internal',
    database: 'analytics-db'
  },
  {
    id: 'SM-1039',
    tableName: 'public.user_profiles',
    shadowTableName: 'public._sm_shadow_user_profiles_1039',
    ddlStatement: 'ALTER TABLE user_profiles ADD COLUMN timezone VARCHAR(64);',
    state: 'COMPLETED',
    sourceRowCount: 1200000,
    rowsBackfilled: 1200000,
    replicationLagBytes: 0,
    createdAt: '2026-09-07T21:00:00Z',
    startedAt: '2026-09-07T21:00:05Z',
    completedAt: '2026-09-07T21:04:16Z',
    initiatedBy: 'ci-cd-runner[bot]',
    database: 'production-db-us-east'
  },
  {
    id: 'SM-1038',
    tableName: 'public.sessions',
    shadowTableName: 'public._sm_shadow_sessions_1038',
    ddlStatement: 'ALTER TABLE sessions ALTER COLUMN token TYPE text;',
    state: 'COMPLETED',
    sourceRowCount: 350000,
    rowsBackfilled: 350000,
    replicationLagBytes: 0,
    createdAt: '2026-09-06T10:13:00Z',
    startedAt: '2026-09-06T10:13:05Z',
    completedAt: '2026-09-06T10:14:13Z',
    initiatedBy: 'maria.k@company.internal',
    database: 'auth-db-replica'
  }
];

export const MOCK_EVENTS = [
  {
    timestamp: '14:32:04.102',
    type: 'BATCH_FLUSH',
    payload: 'Chunk #1256 committed: 5,000 rows backfilled into _sm_shadow_orders_1042',
    status: 'ACK (14ms)'
  },
  {
    timestamp: '14:32:01.890',
    type: 'BATCH_FLUSH',
    payload: 'Chunk #1255 committed: 5,000 rows backfilled into _sm_shadow_orders_1042',
    status: 'ACK (16ms)'
  },
  {
    timestamp: '14:31:58.411',
    type: 'CDC_STREAM_REPLAY',
    payload: 'Synchronized 18 live INSERT/UPDATE mutations during chunk overlap window',
    status: 'SYNCED'
  },
  {
    timestamp: '14:31:45.890',
    type: 'STATE_CHECKPOINT',
    payload: 'Checkpoint persisted in Redis state store: chunk_1250 (pk: 6250000)',
    status: 'ACK (2ms)'
  },
  {
    timestamp: '14:28:10.004',
    type: 'SHADOW_CREATED',
    payload: 'Table public._sm_shadow_orders_1042 provisioned with identical primary keys and constraints',
    status: 'SUCCESS'
  }
];

export const MOCK_LOGS = [
  '2026-09-08T14:28:09.129Z [INFO] [coordinator] Initiating migration sequence for table public.orders',
  '2026-09-08T14:28:09.134Z [INFO] [preflight] Validating lock contention on orders table... Acquired in 1.2ms',
  '2026-09-08T14:28:09.201Z [INFO] [planner] Divided primary key space (id: 1..8200000) into 1,640 chunks of 5,000',
  '2026-09-08T14:28:10.010Z [INFO] [cdc-reader] Logical replication slot `safemigrate_slot_orders` established at LSN 14/D300000',
  '2026-09-08T14:28:10.540Z [INFO] [worker-0] Backfill worker assigned to pool worker-pool-us-east-04',
  '2026-09-08T14:29:12.890Z [INFO] [worker-0] Backfilled slice 0..2000000 (throughput: 9,420 rows/s)',
  '2026-09-08T14:30:45.120Z [INFO] [applier] Applied 482 live CDC mutations from Kafka topic orders.wal',
  '2026-09-08T14:31:45.890Z [INFO] [state] Checkpoint persisted in Redis state store: checkpoint:order:chunk_1256',
  '2026-09-08T14:32:04.102Z [PROGRESS] 6,284,000 / 8,200,000 rows (76.63%) - estimated time to complete backfill: 134s',
  '2026-09-08T14:32:04.155Z [TELEMETRY] wal_lag_ms=142 active_locks=0 replica_safe=true throttle_ratio=0.80'
];
