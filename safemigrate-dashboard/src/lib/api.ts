import { 
  CreateMigrationRequest, 
  MigrationResponse, 
  PreflightReport, 
  ApprovalRequest 
} from './types';

const API_BASE_URL = typeof window !== 'undefined' 
  ? '/api/migrations' 
  : (process.env.INTERNAL_BACKEND_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/migrations');

export async function fetchMigrations(): Promise<MigrationResponse[]> {
  try {
    const res = await fetch(`${API_BASE_URL}`, {
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    return Array.isArray(data) ? data : [];
  } catch (err) {
    console.warn('Could not fetch real migrations from Spring Boot API:', err);
    return [];
  }
}

export async function fetchMigration(id: string): Promise<MigrationResponse | null> {
  try {
    const res = await fetch(`${API_BASE_URL}/${id}`, {
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch (err) {
    console.warn(`Could not fetch migration ${id} from API:`, err);
    return null;
  }
}

export async function submitMigration(request: CreateMigrationRequest): Promise<MigrationResponse> {
  const cleanTable = request.tableName.replace(/^public\./, '').trim();
  const res = await fetch(`${API_BASE_URL}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...request,
      tableName: cleanTable
    })
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(err.message || `Failed to submit migration (HTTP ${res.status})`);
  }
  return await res.json();
}

export async function runPreflight(tableName: string, ddlStatement: string, databaseId?: string): Promise<PreflightReport> {
  const cleanTable = tableName.replace(/^public\./, '').trim();
  const res = await fetch(`${API_BASE_URL}/preflight`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tableName: cleanTable, ddlStatement, databaseId })
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(err.message || `Pre-flight safety check failed (HTTP ${res.status})`);
  }
  return await res.json();
}

export async function approveMigration(id: string, request: ApprovalRequest): Promise<MigrationResponse> {
  const approver = request.approver || request.approvedBy || 'dba@enterprise.internal';
  const res = await fetch(`${API_BASE_URL}/${id}/approve?approver=${encodeURIComponent(approver)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approver, notes: request.notes })
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(err.message || `Approval failed (HTTP ${res.status})`);
  }
  return await res.json();
}

export async function executeCutover(id: string): Promise<MigrationResponse> {
  const res = await fetch(`${API_BASE_URL}/${id}/cutover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(err.message || `Cutover execution failed (HTTP ${res.status})`);
  }
  return await res.json();
}

export async function rollbackMigration(id: string, reason: string): Promise<MigrationResponse> {
  const res = await fetch(`${API_BASE_URL}/${id}/rollback?reason=${encodeURIComponent(reason)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(err.message || `Rollback failed (HTTP ${res.status})`);
  }
  return await res.json();
}

export async function clearFailedMigrations(): Promise<void> {
  try {
    await fetch(`${API_BASE_URL}/failed`, {
      method: 'DELETE',
    });
  } catch (err) {
    console.error('Failed to clear failed migrations:', err);
  }
}
