export type MigrationState =
  | 'INITIALIZING'
  | 'PREFLIGHT_CHECKING'
  | 'BACKFILLING'
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
  approved?: boolean;
  approvedBy?: string;
  approvedAt?: string;
  preflightReport?: PreflightReport;
  errorMessage?: string;
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
  approver?: string;
  approvedBy?: string;
  notes?: string;
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
}
