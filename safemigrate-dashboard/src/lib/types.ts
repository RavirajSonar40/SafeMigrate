export type MigrationState =
  | 'INITIALIZING'
  | 'PREFLIGHT_CHECKING'
  | 'BACKFILLING'
  | 'PAUSED'
  | 'RESUMING'
  | 'CATCHING_UP'
  | 'READY_FOR_CUTOVER'
  | 'READY_CUTOVER'
  | 'CUTTING_OVER'
  | 'COMPLETED'
  | 'ROLLED_BACK'
  | 'FAILED';

export interface PreflightIssue {
  severity: 'WARNING' | 'BLOCKER';
  code: string;
  message: string;
  remediation?: string;
}

export interface PreflightReport {
  tableName: string;
  passed: boolean;
  issues: PreflightIssue[];
  estimatedRows?: number;
  tableSizeBytes?: number;
  activeLocksDetected?: number;
  primaryKeyColumn?: string;
  replicaIdentityFull?: boolean;
  estimatedRequiredDiskBytes?: number;
  availableDiskBytes?: number;
  activeLockContention?: boolean;
  warnings?: string[];
  errors?: string[];
}

export interface MigrationResponse {
  id: string;
  tableName: string;
  shadowTableName: string;
  ddlStatement?: string;
  state: MigrationState;
  totalSourceRows?: number;
  sourceRowCount?: number;
  rowsBackfilled?: number;
  progressPercentage?: number;
  replicationLagBytes?: number;
  cutoverDurationMs?: number;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  initiatedBy?: string;
  database?: string;
  databaseId?: string;
  approved?: boolean;
  approvedBy?: string;
  approvedAt?: string;
  preflightReport?: PreflightReport;
  errorMessage?: string;
  reconciliationReport?: ReconciliationReport;
  sourceLsn?: string;
  appliedLsnStr?: string;
  divergenceEvents?: number;
  lastCheckpointPk?: number;
  activeChaosAction?: string | null;
  resilienceEvents?: string[];
  resilienceLogs?: string[];
  oldTableName?: string;
  slotName?: string;
  kafkaTopic?: string;
  lastAppliedLsn?: number | string;
  appliedInserts?: number;
  appliedUpdates?: number;
  appliedDeletes?: number;
  totalApplied?: number;
}

export interface ReconciliationReport {
  tableName: string;
  comparedTable: string;
  matched: boolean;
  sourceRowCount: number;
  targetRowCount: number;
  sourceChecksum: number | string;
  targetChecksum: number | string;
  comparedColumns: string[];
  discrepancyCount: number;
  sampleDiscrepancies: string[];
  executionTimeMs: number;
  verifiedAt: string;
  id?: string;
  migrationId?: string;
  shadowRowCount?: number;
  sourceHash?: string;
  shadowHash?: string;
  mismatchedRowsCount?: number;
  divergentKeys?: string[];
  status?: string;
  createdAt?: string;
}

export interface CreateMigrationRequest {
  tableName: string;
  ddlStatement: string;
  databaseId?: string;
  batchSize?: number;
  maxAllowedLagBytes?: number;
  maxWaitTimeoutMs?: number;
  lockTimeoutMs?: number;
}

export interface ApprovalRequest {
  approver: string;
  notes?: string;
}

export interface RollbackRequest {
  reason: string;
}

export interface MigrationProgressEvent {
  migrationId: string;
  tableName: string;
  state: MigrationState;
  rowsBackfilled: number;
  totalSourceRows: number;
  progressPercentage: number;
  replicationLagBytes: number;
  appliedInserts: number;
  appliedUpdates: number;
  appliedDeletes: number;
  totalApplied: number;
  timestamp: number;
  errorMessage?: string;
  sourceLsn?: string;
  appliedLsnStr?: string;
  divergenceEvents?: number;
}

export type ChaosAction =
  | 'KILL_WAL_READER'
  | 'KILL_BACKFILL_WORKER'
  | 'KILL_CHANGE_APPLIER'
  | 'INJECT_LATENCY_2S'
  | 'PAUSE_KAFKA';

export interface ChaosInjectionRequest {
  action: ChaosAction;
  notes?: string;
}

export interface ChaosInjectionResponse {
  migrationId: string;
  action: ChaosAction;
  success: boolean;
  message: string;
  affectedComponent: string;
  previousState: string;
  currentState: string;
  checkpointPk?: number;
  injectedAt: string;
}

export interface RecoveryResponse {
  migrationId: string;
  recovered: boolean;
  message: string;
  restoredComponent: string;
  resumedFromPk?: number;
  eventsLost: number;
  duplicateEvents: number;
  currentState: string;
  recoveredAt: string;
}

export interface ForeignKeyRef {
  constraintName: string;
  sourceTable: string;
  sourceColumn: string;
  targetTable: string;
  targetColumn: string;
  onUpdate?: string;
  onDelete?: string;
  onUpdateAction?: string;
  onDeleteAction?: string;
}

export type ForeignKeyDetail = ForeignKeyRef;

export interface TriggerRef {
  triggerName: string;
  event: string;
  timing: string;
  actionStatement: string;
  tableName?: string;
  orientation?: string;
}

export type TriggerDetail = TriggerRef;

export interface DependencyGraphDto {
  tableName: string;
  incomingForeignKeys: ForeignKeyRef[];
  outgoingForeignKeys: ForeignKeyRef[];
  triggers: TriggerRef[];
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | string;
  warnings?: string[];
  totalDependencies?: number;
  hasCycleRisk?: boolean;
  lockEscalationRisk?: string;
  recommendedOrder?: string[];
}

export interface MigrationPlanDto {
  tableName: string;
  ddlStatement: string;
  databaseId?: string;
  estimatedRows: number;
  tableSizeBytes: number;
  tableSizeBytesFormatted: string;
  estimatedDurationSeconds: number;
  estimatedDurationFormatted: string;
  estimatedWalBytes: number;
  estimatedWalBytesFormatted: string;
  requiredDiskBytes: number;
  requiredDiskBytesFormatted: string;
  expectedCpuLoadPct: number;
  expectedCutoverDurationMs: number;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  riskFactors: string[];
  dependencies: DependencyGraphDto;
  recommendedBatchSize: number;
  recommendedThrottleDelayMs: number;
  safeToExecute: boolean;
}

export interface WorkerStatusDto {
  id?: string;
  name?: string;
  component?: 'WAL_READER' | 'BACKFILL_WORKER' | 'CHANGE_APPLIER' | 'CUTOVER_COORDINATOR' | string;
  status: 'HEALTHY' | 'RUNNING' | 'PAUSED' | 'INTERRUPTED' | 'IDLE' | 'COMPLETED' | 'ERROR' | string;
  lsn?: string;
  offset?: number;
  currentPk?: number;
  throughputEventsPerSec?: number;
  lagBytes?: number;
  lagMs?: number;
  message?: string;
  lastHeartbeat?: string;
  metrics?: Record<string, unknown>;
  // Convenience aliases for UI bindings
  workerId?: string;
  type?: string;
  currentThroughput?: string;
  totalProcessed?: number;
  activeThreadCount?: number;
  details?: string;
}
