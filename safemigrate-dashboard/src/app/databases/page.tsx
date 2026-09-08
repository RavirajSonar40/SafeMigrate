'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import Sidebar from '@/components/layout/Sidebar';
import Header from '@/components/layout/Header';

interface DatabaseConnection {
  id: string;
  name: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  sslMode: boolean;
  isDefault: boolean;
  status: string;
  walLevel: string;
  postgresVersion?: string;
  latencyMs?: number;
}

interface TestResult {
  connected: boolean;
  latencyMs: number;
  postgresVersion?: string;
  walLevel?: string;
  walLevelValid?: boolean;
  hasReplicationRole?: boolean;
  maxReplicationSlots?: number;
  message?: string;
}

export default function DatabasesPage() {
  const [databases, setDatabases] = useState<DatabaseConnection[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [testingConnection, setTestingConnection] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [saveLoading, setSaveLoading] = useState(false);

  // Form State
  const [name, setName] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState(5432);
  const [databaseName, setDatabaseName] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [sslMode, setSslMode] = useState(false);

  useEffect(() => {
    fetchDatabases();
  }, []);

  const fetchDatabases = async () => {
    try {
      setLoading(true);
      const res = await fetch('/api/databases');
      if (res.ok) {
        const data = await res.json();
        setDatabases(data);
      }
    } catch (err) {
      console.error('Failed to fetch databases:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleTestConnection = async () => {
    setTestingConnection(true);
    setTestResult(null);
    try {
      const res = await fetch('/api/databases/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name || 'Test Connection',
          host,
          port: Number(port),
          databaseName,
          username,
          password,
          sslMode,
        }),
      });
      const data: TestResult = await res.json();
      setTestResult(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Network error testing connection';
      setTestResult({
        connected: false,
        latencyMs: 0,
        message: msg,
      });
    } finally {
      setTestingConnection(false);
    }
  };

  const handleSaveDatabase = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name || !host || !databaseName || !username) return;

    setSaveLoading(true);
    try {
      const res = await fetch('/api/databases', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          host,
          port: Number(port),
          databaseName,
          username,
          password,
          sslMode,
        }),
      });
      if (res.ok) {
        setShowAddModal(false);
        // Reset form
        setName('');
        setHost('');
        setDatabaseName('');
        setUsername('');
        setPassword('');
        setTestResult(null);
        fetchDatabases();
      }
    } catch (err) {
      console.error('Failed to save database:', err);
    } finally {
      setSaveLoading(false);
    }
  };

  const handleDeleteDatabase = async (id: string) => {
    if (!confirm('Are you sure you want to disconnect this database?')) return;
    try {
      const res = await fetch(`/api/databases/${id}`, { method: 'DELETE' });
      if (res.ok) {
        fetchDatabases();
      }
    } catch (err) {
      console.error('Failed to delete database:', err);
    }
  };

  return (
    <div className="flex h-screen bg-surface selection:bg-primary/30 overflow-hidden font-sans">
      <Sidebar />
      <div className="flex-1 ml-[220px] flex flex-col h-full overflow-y-auto">
        <Header />

        <main className="p-8 max-w-7xl w-full mx-auto flex flex-col gap-6">
          {/* Header Bar */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-outline-variant/15 pb-6">
            <div>
              <div className="flex items-center gap-2 mb-1.5">
                <span className="material-symbols-outlined text-primary text-[24px]">database</span>
                <h1 className="text-2xl font-bold text-on-surface tracking-tight font-mono">
                  Database Connections
                </h1>
              </div>
              <p className="text-xs text-on-surface-variant max-w-2xl font-sans">
                Connect external PostgreSQL databases (AWS RDS, Aurora, Supabase, Neon, or on-premises) for zero-downtime schema migrations.
              </p>
            </div>

            <button
              onClick={() => {
                setShowAddModal(true);
                setTestResult(null);
              }}
              className="px-4 py-2.5 rounded-xl bg-primary text-surface-container-lowest text-xs font-bold font-mono hover:bg-primary-fixed transition-all flex items-center gap-2 shadow-lg shadow-primary/20 shrink-0 cursor-pointer"
            >
              <span className="material-symbols-outlined text-[18px]">add</span>
              <span>Connect PostgreSQL Database</span>
            </button>
          </div>

          {/* Database Cards */}
          {loading ? (
            <div className="flex items-center justify-center p-12 text-xs font-mono text-outline">
              <span className="animate-spin mr-2">◌</span> Loading registered databases...
            </div>
          ) : databases.length === 0 ? (
            <div className="p-12 rounded-2xl bg-surface-container-low border border-dashed border-outline-variant/30 text-center flex flex-col items-center gap-3">
              <span className="material-symbols-outlined text-4xl text-outline">database_off</span>
              <p className="text-sm font-semibold text-on-surface">No Databases Connected</p>
              <p className="text-xs text-on-surface-variant max-w-md">
                Add your PostgreSQL database connection to start zero-lock schema migrations and live CDC change stream replication.
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {databases.map((db) => (
                <div
                  key={db.id}
                  className="p-5 rounded-2xl bg-surface-container-lowest border border-outline-variant/30 hover:border-primary/40 transition-all flex flex-col justify-between gap-4 shadow-sm"
                >
                  <div className="flex flex-col gap-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex items-center gap-2.5">
                        <div className="w-10 h-10 rounded-xl bg-primary/10 border border-primary/20 flex items-center justify-center text-primary">
                          <span className="material-symbols-outlined text-[20px]">storage</span>
                        </div>
                        <div>
                          <h3 className="text-sm font-bold text-on-surface font-mono flex items-center gap-2">
                            {db.name}
                            {db.isDefault && (
                              <span className="text-[10px] font-mono px-2 py-0.2 rounded-full bg-primary/10 text-primary border border-primary/30">
                                PRIMARY ENGINE
                              </span>
                            )}
                          </h3>
                          <span className="text-xs font-mono text-on-surface-variant">
                            {db.username}@{db.host}:{db.port}/{db.databaseName}
                          </span>
                        </div>
                      </div>

                      <span
                        className={`text-[10px] font-mono px-2 py-0.5 rounded border ${
                          db.status === 'CONNECTED'
                            ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                            : 'bg-rose-500/10 text-rose-400 border-rose-500/20'
                        }`}
                      >
                        {db.status === 'CONNECTED' ? '● ONLINE' : 'DISCONNECTED'}
                      </span>
                    </div>

                    {/* Metadata specs */}
                    <div className="grid grid-cols-3 gap-2 p-3 rounded-xl bg-surface-container-low border border-outline-variant/15 text-xs font-mono">
                      <div>
                        <span className="text-[10px] text-outline block">WAL LEVEL</span>
                        <span className="text-primary font-semibold uppercase">{db.walLevel || 'logical'}</span>
                      </div>
                      <div>
                        <span className="text-[10px] text-outline block">SSL MODE</span>
                        <span className="text-on-surface">{db.sslMode ? 'Required' : 'Disabled'}</span>
                      </div>
                      <div>
                        <span className="text-[10px] text-outline block">LATENCY</span>
                        <span className="text-on-surface font-semibold">{db.latencyMs ?? 1} ms</span>
                      </div>
                    </div>
                  </div>

                  {/* Action Buttons */}
                  <div className="flex items-center justify-between pt-3 border-t border-outline-variant/15 text-xs font-mono">
                    <div className="flex items-center gap-2">
                      <Link
                        href={`/explorer?db=${db.id}`}
                        className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-on-surface font-medium transition-colors flex items-center gap-1.5"
                      >
                        <span className="material-symbols-outlined text-[16px] text-primary">visibility</span>
                        <span>See Inside DB</span>
                      </Link>
                      <Link
                        href={`/migrations/new?db=${db.id}`}
                        className="px-3 py-1.5 rounded-lg bg-primary/10 hover:bg-primary/20 text-primary border border-primary/30 font-semibold transition-colors flex items-center gap-1.5"
                      >
                        <span className="material-symbols-outlined text-[16px]">rocket_launch</span>
                        <span>Migrate</span>
                      </Link>
                    </div>

                    {!db.isDefault && (
                      <button
                        onClick={() => handleDeleteDatabase(db.id)}
                        className="text-outline hover:text-error transition-colors p-1"
                        title="Disconnect database"
                      >
                        <span className="material-symbols-outlined text-[18px]">delete</span>
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </main>
      </div>

      {/* Connect Database Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5 max-h-[90vh] overflow-y-auto font-sans">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-2.5">
                <span className="material-symbols-outlined text-primary text-[24px]">add_to_queue</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Connect PostgreSQL Database</h3>
                  <p className="text-xs text-on-surface-variant">Configure target instance for zero-downtime migrations</p>
                </div>
              </div>
              <button
                onClick={() => setShowAddModal(false)}
                className="text-outline hover:text-on-surface p-1"
              >
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <form onSubmit={handleSaveDatabase} className="flex flex-col gap-4 text-xs font-mono">
              <div className="flex flex-col gap-1.5">
                <label className="text-on-surface-variant font-medium">Connection Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Production RDS Aurora / Supabase Main"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                />
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div className="col-span-2 flex flex-col gap-1.5">
                  <label className="text-on-surface-variant font-medium">Host / IP</label>
                  <input
                    type="text"
                    required
                    placeholder="db.production.internal / 15.x.x.x"
                    value={host}
                    onChange={(e) => setHost(e.target.value)}
                    className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-on-surface-variant font-medium">Port</label>
                  <input
                    type="number"
                    required
                    value={port}
                    onChange={(e) => setPort(Number(e.target.value))}
                    className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                  />
                </div>
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-on-surface-variant font-medium">Database Name</label>
                <input
                  type="text"
                  required
                  placeholder="postgres / safemigrate_prod"
                  value={databaseName}
                  onChange={(e) => setDatabaseName(e.target.value)}
                  className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-1.5">
                  <label className="text-on-surface-variant font-medium">Username</label>
                  <input
                    type="text"
                    required
                    placeholder="postgres"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-on-surface-variant font-medium">Password</label>
                  <input
                    type="password"
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                  />
                </div>
              </div>

              <div className="flex items-center gap-2 pt-1">
                <input
                  type="checkbox"
                  id="sslToggle"
                  checked={sslMode}
                  onChange={(e) => setSslMode(e.target.checked)}
                  className="rounded border-outline-variant/40 bg-surface-container text-primary"
                />
                <label htmlFor="sslToggle" className="text-on-surface text-xs select-none cursor-pointer">
                  Require SSL Mode (<span className="text-outline">sslmode=require</span>)
                </label>
              </div>

              {/* Test Diagnostics Result Box */}
              {testResult && (
                <div
                  className={`p-3.5 rounded-xl border flex flex-col gap-2 ${
                    testResult.connected
                      ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
                      : 'bg-rose-500/10 border-rose-500/30 text-rose-400'
                  }`}
                >
                  <div className="flex items-center gap-2 font-bold text-xs">
                    <span className="material-symbols-outlined text-[18px]">
                      {testResult.connected ? 'check_circle' : 'error'}
                    </span>
                    <span>{testResult.connected ? 'Connection Succeeded' : 'Connection Failed'}</span>
                    {testResult.latencyMs > 0 && (
                      <span className="ml-auto text-[11px] font-normal text-on-surface-variant">
                        Latency: {testResult.latencyMs}ms
                      </span>
                    )}
                  </div>

                  <p className="text-xs text-on-surface-variant font-sans">{testResult.message}</p>

                  {testResult.connected && (
                    <div className="grid grid-cols-2 gap-2 text-[11px] pt-1 border-t border-outline-variant/15 text-on-surface">
                      <div>
                        <span className="text-outline">Engine: </span>
                        <span>{testResult.postgresVersion || 'PostgreSQL'}</span>
                      </div>
                      <div>
                        <span className="text-outline">wal_level: </span>
                        <span className={testResult.walLevelValid ? 'text-primary font-bold' : 'text-amber-400'}>
                          {testResult.walLevel}
                        </span>
                      </div>
                      <div>
                        <span className="text-outline">Replication Privileges: </span>
                        <span>{testResult.hasReplicationRole ? 'Granted' : 'Standard'}</span>
                      </div>
                      <div>
                        <span className="text-outline">Max Slots: </span>
                        <span>{testResult.maxReplicationSlots}</span>
                      </div>
                    </div>
                  )}
                </div>
              )}

              <div className="flex items-center justify-between pt-3 border-t border-outline-variant/15">
                <button
                  type="button"
                  onClick={handleTestConnection}
                  disabled={testingConnection || !host || !databaseName || !username}
                  className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-semibold text-on-surface transition-colors flex items-center gap-2 disabled:opacity-40"
                >
                  {testingConnection ? (
                    <>
                      <span className="animate-spin text-[14px]">◌</span>
                      <span>Testing Ping &amp; WAL...</span>
                    </>
                  ) : (
                    <>
                      <span className="material-symbols-outlined text-[16px] text-primary">network_check</span>
                      <span>Test Diagnostics</span>
                    </>
                  )}
                </button>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setShowAddModal(false)}
                    className="px-4 py-2 rounded-lg bg-surface-container text-xs font-medium text-on-surface"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={saveLoading || !name || !host || !databaseName || !username}
                    className="px-4 py-2 rounded-lg bg-primary text-surface-container-lowest text-xs font-bold hover:bg-primary-fixed transition-colors disabled:opacity-40"
                  >
                    {saveLoading ? 'Connecting...' : 'Save & Connect'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
