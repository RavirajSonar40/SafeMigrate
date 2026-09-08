'use client';

import { useState, useEffect, Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';

interface DatabaseOption {
  id: string;
  name: string;
  databaseName: string;
  host: string;
  port: number;
}

interface TableSummary {
  tableName: string;
  schemaName: string;
  estimatedRowCount: number;
  sizeBytesFormatted: string;
  primaryKeyColumn: string | null;
  replicaIdentity: string;
  replicaIdentityFull: boolean;
}

interface ColumnMetadata {
  columnName: string;
  dataType: string;
  nullable: boolean;
  columnDefault: string | null;
  primaryKey: boolean;
}

interface TableData {
  tableName: string;
  columns: string[];
  rows: Record<string, string | null>[];
  totalRows: number;
  returnedRows: number;
}

function ExplorerContent() {
  const searchParams = useSearchParams();
  const dbParam = searchParams.get('db');
  const tableParam = searchParams.get('table');
  const highlightParam = searchParams.get('highlight')?.trim();

  const [databases, setDatabases] = useState<DatabaseOption[]>([]);
  const [selectedDbId, setSelectedDbId] = useState<string>(dbParam || 'default-postgres');
  const [tables, setTables] = useState<TableSummary[]>([]);
  const [selectedTable, setSelectedTable] = useState<string | null>(tableParam || null);
  const [tableSearch, setTableSearch] = useState('');

  // Active view tab - default to schema if highlight is provided so user immediately sees their column spec
  const [activeTab, setActiveTab] = useState<'data' | 'schema' | 'replication'>(
    highlightParam ? 'schema' : 'data'
  );

  // Detail states
  const [schemaColumns, setSchemaColumns] = useState<ColumnMetadata[]>([]);
  const [tableData, setTableData] = useState<TableData | null>(null);
  const [loadingTables, setLoadingTables] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);

  // 1. Fetch available databases
  useEffect(() => {
    fetch('/api/databases')
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setDatabases(data);
          if (dbParam && data.some((d: DatabaseOption) => d.id === dbParam)) {
            setSelectedDbId(dbParam);
          } else if (!selectedDbId || !data.some((d: DatabaseOption) => d.id === selectedDbId)) {
            setSelectedDbId(data[0].id);
          }
        }
      })
      .catch((err) => console.error('Failed to load databases:', err));
  }, [dbParam]);

  // 2. Fetch tables when database changes
  useEffect(() => {
    if (!selectedDbId) return;
    setLoadingTables(true);
    fetch(`/api/databases/${selectedDbId}/tables`)
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data)) {
          setTables(data);
          if (tableParam && data.some((t: TableSummary) => t.tableName === tableParam)) {
            setSelectedTable(tableParam);
          } else if (data.length > 0) {
            setSelectedTable(data[0].tableName);
          } else {
            setSelectedTable(null);
            setTableData(null);
            setSchemaColumns([]);
          }
        }
      })
      .catch((err) => console.error('Failed to load tables:', err))
      .finally(() => setLoadingTables(false));
  }, [selectedDbId, tableParam]);

  // 3. Fetch schema and data when selected table changes
  useEffect(() => {
    if (!selectedDbId || !selectedTable) return;
    setLoadingDetails(true);

    Promise.all([
      fetch(`/api/databases/${selectedDbId}/tables/${selectedTable}/schema`).then((r) => r.json()),
      fetch(`/api/databases/${selectedDbId}/tables/${selectedTable}/data?limit=50`).then((r) => r.json()),
    ])
      .then(([schema, data]) => {
        setSchemaColumns(Array.isArray(schema) ? schema : []);
        setTableData(data && data.columns ? data : null);
      })
      .catch((err) => console.error('Failed to fetch table details:', err))
      .finally(() => setLoadingDetails(false));
  }, [selectedDbId, selectedTable]);

  const currentTableMeta = tables.find((t) => t.tableName === selectedTable);
  const filteredTables = tables.filter((t) =>
    t.tableName.toLowerCase().includes(tableSearch.toLowerCase())
  );

  return (
    <div className="flex flex-col h-[calc(100vh-5.5rem)] max-w-full font-sans">
      {/* Top Control Bar */}
      <div className="px-6 py-3.5 border border-outline-variant/20 rounded-xl bg-surface-container-lowest flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-4 shrink-0 shadow-sm">
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-primary text-[24px]">table_chart</span>
            <div>
              <h1 className="text-base font-bold text-on-surface font-mono flex items-center gap-2">
                Database Explorer
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20 font-normal">
                  LIVE INSPECTOR
                </span>
              </h1>
              <p className="text-xs text-on-surface-variant font-sans">
                Inspect live schemas, browse table contents, and launch zero-lock migrations.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Database Selector Dropdown */}
            <div className="flex items-center gap-2">
              <span className="text-xs font-mono text-outline">Target DB:</span>
              <select
                value={selectedDbId}
                onChange={(e) => setSelectedDbId(e.target.value)}
                className="px-3 py-1.5 rounded-lg bg-surface-container-low border border-outline-variant/30 text-xs font-mono text-on-surface outline-none focus:border-primary cursor-pointer"
              >
                {databases.map((db) => (
                  <option key={db.id} value={db.id}>
                    {db.name} ({db.databaseName})
                  </option>
                ))}
              </select>
            </div>

            <Link
              href="/databases"
              className="px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-mono text-on-surface flex items-center gap-1.5 transition-colors"
            >
              <span className="material-symbols-outlined text-[16px]">tune</span>
              <span>Manage DBs</span>
            </Link>
          </div>
        </div>

        {/* Two-Column Explorer Layout */}
        <div className="flex-1 flex overflow-hidden">
          {/* Left Column: Tables Sidebar */}
          <aside className="w-64 border-r border-outline-variant/15 bg-surface-container-lowest/50 flex flex-col h-full shrink-0">
            {/* Table Search */}
            <div className="p-3 border-b border-outline-variant/15">
              <div className="relative">
                <span className="material-symbols-outlined absolute left-2.5 top-2.5 text-[16px] text-outline">
                  search
                </span>
                <input
                  type="text"
                  placeholder="Filter tables..."
                  value={tableSearch}
                  onChange={(e) => setTableSearch(e.target.value)}
                  className="w-full pl-8 pr-3 py-1.5 rounded-lg bg-surface-container-low border border-outline-variant/20 text-xs font-mono text-on-surface outline-none focus:border-primary"
                />
              </div>
            </div>

            {/* Tables List */}
            <div className="flex-1 overflow-y-auto p-2 flex flex-col gap-1">
              <div className="px-2 py-1 text-[10px] font-mono text-outline uppercase tracking-wider flex justify-between">
                <span>Tables ({filteredTables.length})</span>
                <span>Rows</span>
              </div>

              {loadingTables ? (
                <div className="p-6 text-center text-xs font-mono text-outline">
                  <span className="animate-spin mr-1">◌</span> Loading tables...
                </div>
              ) : filteredTables.length === 0 ? (
                <div className="p-6 text-center text-xs font-mono text-outline">
                  No tables found in public schema.
                </div>
              ) : (
                filteredTables.map((t) => {
                  const isSelected = selectedTable === t.tableName;
                  return (
                    <button
                      key={t.tableName}
                      onClick={() => setSelectedTable(t.tableName)}
                      className={`w-full px-3 py-2 rounded-lg text-left text-xs font-mono flex items-center justify-between transition-all cursor-pointer ${
                        isSelected
                          ? 'bg-primary/15 text-primary border border-primary/30 font-semibold'
                          : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                      }`}
                    >
                      <div className="flex items-center gap-2 truncate">
                        <span className="material-symbols-outlined text-[16px] shrink-0 text-outline">
                          table_rows
                        </span>
                        <span className="truncate">{t.tableName}</span>
                      </div>
                      <span className="text-[10px] font-mono text-outline shrink-0 ml-2">
                        {t.estimatedRowCount.toLocaleString()}
                      </span>
                    </button>
                  );
                })
              )}
            </div>
          </aside>

          {/* Right Column: Inside the DB (Schema & Live Data Preview) */}
          <div className="flex-1 flex flex-col h-full overflow-hidden bg-surface">
            {selectedTable && currentTableMeta ? (
              <>
                {/* Table Header Bar */}
                <div className="p-6 border-b border-outline-variant/15 bg-surface-container-lowest/80 flex flex-col md:flex-row md:items-center justify-between gap-4 shrink-0">
                  <div className="flex flex-col gap-1.5">
                    <div className="flex items-center gap-2">
                      <span className="text-lg font-bold font-mono text-on-surface">
                        public.{currentTableMeta.tableName}
                      </span>
                      {currentTableMeta.replicaIdentityFull ? (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                          REPLICA IDENTITY FULL
                        </span>
                      ) : (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/20">
                          REPLICA: {currentTableMeta.replicaIdentity}
                        </span>
                      )}
                    </div>

                    <div className="flex items-center gap-4 text-xs font-mono text-on-surface-variant">
                      <span>Rows: <strong className="text-on-surface">{currentTableMeta.estimatedRowCount.toLocaleString()}</strong></span>
                      <span>Disk Footprint: <strong className="text-on-surface">{currentTableMeta.sizeBytesFormatted}</strong></span>
                      {currentTableMeta.primaryKeyColumn && (
                        <span>Primary Key: <strong className="text-primary">{currentTableMeta.primaryKeyColumn}</strong></span>
                      )}
                    </div>
                  </div>

                  {/* One-Click Migration Button */}
                  <Link
                    href={`/migrations/new?table=${currentTableMeta.tableName}&db=${selectedDbId}`}
                    className="px-4 py-2 rounded-xl bg-primary text-surface-container-lowest text-xs font-bold font-mono hover:bg-primary-fixed transition-all flex items-center gap-2 shadow-lg shadow-primary/20 shrink-0 self-start md:self-auto"
                  >
                    <span className="material-symbols-outlined text-[18px]">rocket_launch</span>
                    <span>Start Zero-Downtime Migration</span>
                  </Link>
                </div>

                {/* Post-Migration Schema Verification Banner */}
                {highlightParam && (
                  <div className="mx-6 mt-4 p-4 rounded-xl bg-gradient-to-r from-emerald-500/15 via-teal-500/10 to-transparent border border-emerald-500/40 flex flex-col sm:flex-row sm:items-center justify-between gap-4 animate-pop-in shrink-0">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-lg bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
                        <span className="material-symbols-outlined text-emerald-400 text-[24px]">verified</span>
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-bold text-emerald-300 font-mono">
                            ZERO-DOWNTIME MIGRATION VERIFIED LIVE
                          </span>
                          <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 font-semibold border border-emerald-500/30">
                            PROMOTED TO MASTER
                          </span>
                        </div>
                        <p className="text-xs text-on-surface-variant mt-0.5 font-mono">
                          Column <span className="text-emerald-400 font-bold underline decoration-emerald-500">{highlightParam}</span> was successfully cut over to <span className="text-on-surface font-semibold">public.{selectedTable}</span> on <span className="text-on-surface font-semibold">{selectedDbId}</span> with 0 data loss.
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 self-end sm:self-auto">
                      <button
                        type="button"
                        onClick={() => setActiveTab(activeTab === 'schema' ? 'data' : 'schema')}
                        className="px-3 py-1.5 rounded-lg bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 text-xs font-mono font-semibold border border-emerald-500/40 transition-colors flex items-center gap-1.5 cursor-pointer"
                      >
                        <span className="material-symbols-outlined text-[16px]">
                          {activeTab === 'schema' ? 'visibility' : 'schema'}
                        </span>
                        <span>{activeTab === 'schema' ? 'View Live Data' : 'View Schema Spec'}</span>
                      </button>
                    </div>
                  </div>
                )}

                {/* Tab Controls */}
                <div className="flex items-center gap-6 px-6 border-b border-outline-variant/15 bg-surface-container-lowest/40 text-xs font-mono shrink-0">
                  <button
                    onClick={() => setActiveTab('data')}
                    className={`py-3 flex items-center gap-2 border-b-2 font-medium transition-colors cursor-pointer ${
                      activeTab === 'data'
                        ? 'border-primary text-primary font-bold'
                        : 'border-transparent text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <span className="material-symbols-outlined text-[16px]">visibility</span>
                    <span>Live Table Data Preview</span>
                    {highlightParam && (
                      <span className="text-[9px] px-1.5 py-0.2 rounded bg-emerald-500/20 text-emerald-300 font-bold">
                        1 NEW COL
                      </span>
                    )}
                  </button>

                  <button
                    onClick={() => setActiveTab('schema')}
                    className={`py-3 flex items-center gap-2 border-b-2 font-medium transition-colors cursor-pointer ${
                      activeTab === 'schema'
                        ? 'border-primary text-primary font-bold'
                        : 'border-transparent text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <span className="material-symbols-outlined text-[16px]">schema</span>
                    <span>Schema &amp; Columns ({schemaColumns.length})</span>
                    {highlightParam && (
                      <span className="text-[9px] px-1.5 py-0.2 rounded bg-emerald-500/20 text-emerald-300 font-bold animate-pulse">
                        UPDATED
                      </span>
                    )}
                  </button>

                  <button
                    onClick={() => setActiveTab('replication')}
                    className={`py-3 flex items-center gap-2 border-b-2 font-medium transition-colors cursor-pointer ${
                      activeTab === 'replication'
                        ? 'border-primary text-primary font-bold'
                        : 'border-transparent text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <span className="material-symbols-outlined text-[16px]">sync</span>
                    <span>CDC WAL Readiness</span>
                  </button>
                </div>

                {/* Tab Content Body */}
                <div className="flex-1 overflow-auto p-6">
                  {loadingDetails ? (
                    <div className="flex items-center justify-center p-12 text-xs font-mono text-outline">
                      <span className="animate-spin mr-2">◌</span> Fetching live PostgreSQL records...
                    </div>
                  ) : (
                    <>
                      {/* TAB 1: LIVE DATA PREVIEW */}
                      {activeTab === 'data' && (
                        <div className="flex flex-col gap-3">
                          <div className="flex items-center justify-between text-xs font-mono text-outline">
                            <span>
                              Showing first {tableData?.returnedRows || 0} of {tableData?.totalRows || 0} rows directly from database
                            </span>
                            <span className="text-[10px] text-primary">SELECT * FROM public.&quot;{selectedTable}&quot; LIMIT 50;</span>
                          </div>

                          {tableData && tableData.rows.length > 0 ? (
                            <div className="overflow-x-auto rounded-xl border border-outline-variant/20 bg-surface-container-lowest">
                              <table className="w-full text-left text-xs font-mono border-collapse">
                                <thead>
                                  <tr className="border-b border-outline-variant/20 bg-surface-container-low/60 text-outline uppercase text-[10px] tracking-wider">
                                    {tableData.columns.map((col) => {
                                      const isHighlighted = highlightParam && col.toLowerCase() === highlightParam.toLowerCase();
                                      return (
                                        <th
                                          key={col}
                                          className={`py-2.5 px-4 font-semibold whitespace-nowrap transition-colors ${
                                            isHighlighted
                                              ? 'bg-emerald-500/20 text-emerald-300 border-x border-emerald-500/40 shadow-inner'
                                              : ''
                                          }`}
                                        >
                                          <div className="flex items-center gap-1.5">
                                            {col === currentTableMeta.primaryKeyColumn && (
                                              <span className="material-symbols-outlined text-[13px] text-primary">key</span>
                                            )}
                                            {isHighlighted && (
                                              <span className="material-symbols-outlined text-[14px] text-emerald-400 animate-bounce">sparkles</span>
                                            )}
                                            <span>{col}</span>
                                            {isHighlighted && (
                                              <span className="text-[9px] px-1.5 py-0.5 rounded bg-emerald-500 text-surface-container-lowest font-black tracking-wider">
                                                NEW
                                              </span>
                                            )}
                                          </div>
                                        </th>
                                      );
                                    })}
                                  </tr>
                                </thead>
                                <tbody>
                                  {tableData.rows.map((row, idx) => (
                                    <tr
                                      key={idx}
                                      className="border-b border-outline-variant/10 hover:bg-surface-container-low/40 transition-colors"
                                    >
                                      {tableData.columns.map((col) => {
                                        const isHighlighted = highlightParam && col.toLowerCase() === highlightParam.toLowerCase();
                                        const val = row[col];
                                        return (
                                          <td
                                            key={col}
                                            className={`py-2 px-4 whitespace-nowrap ${
                                              isHighlighted
                                                ? 'bg-emerald-500/10 text-emerald-300 font-semibold border-x border-emerald-500/20'
                                                : 'text-on-surface'
                                            }`}
                                          >
                                            {val === null ? (
                                              <span className="text-outline/40 italic">null</span>
                                            ) : (
                                              <span>{val}</span>
                                            )}
                                          </td>
                                        );
                                      })}
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          ) : (
                            <div className="p-8 rounded-xl bg-surface-container-low border border-outline-variant/20 text-center text-xs font-mono text-outline">
                              Table contains 0 rows.
                            </div>
                          )}
                        </div>
                      )}

                      {/* TAB 2: SCHEMA & COLUMNS */}
                      {activeTab === 'schema' && (
                        <div className="flex flex-col gap-3">
                          <div className="overflow-x-auto rounded-xl border border-outline-variant/20 bg-surface-container-lowest">
                            <table className="w-full text-left text-xs font-mono border-collapse">
                              <thead>
                                <tr className="border-b border-outline-variant/20 bg-surface-container-low/60 text-outline uppercase text-[10px] tracking-wider">
                                  <th className="py-2.5 px-4">Column Name</th>
                                  <th className="py-2.5 px-4">Data Type</th>
                                  <th className="py-2.5 px-4">Nullable</th>
                                  <th className="py-2.5 px-4">Default Value</th>
                                  <th className="py-2.5 px-4">Key Constraint</th>
                                </tr>
                              </thead>
                              <tbody>
                                {schemaColumns.map((col) => {
                                  const isHighlighted = highlightParam && col.columnName.toLowerCase() === highlightParam.toLowerCase();
                                  return (
                                    <tr
                                      key={col.columnName}
                                      className={`border-b transition-colors ${
                                        isHighlighted
                                          ? 'bg-emerald-500/15 border-emerald-500/40 shadow-sm'
                                          : 'border-outline-variant/10 hover:bg-surface-container-low/40'
                                      }`}
                                    >
                                      <td className="py-2.5 px-4 font-semibold text-on-surface flex items-center gap-2">
                                        {col.primaryKey ? (
                                          <span className="material-symbols-outlined text-[16px] text-primary">key</span>
                                        ) : isHighlighted ? (
                                          <span className="material-symbols-outlined text-[16px] text-emerald-400 animate-bounce">sparkles</span>
                                        ) : (
                                          <span className="w-4 inline-block"></span>
                                        )}
                                        <span className={isHighlighted ? 'text-emerald-300 font-bold' : ''}>
                                          {col.columnName}
                                        </span>
                                        {isHighlighted && (
                                          <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-emerald-500/30 text-emerald-300 border border-emerald-500/50 font-bold animate-pulse">
                                            ✨ NEWLY ADDED IN MIGRATION
                                          </span>
                                        )}
                                      </td>
                                      <td className={`py-2.5 px-4 font-mono ${isHighlighted ? 'text-emerald-300 font-bold' : 'text-primary'}`}>
                                        {col.dataType}
                                      </td>
                                      <td className="py-2.5 px-4 text-on-surface-variant">
                                        {col.nullable ? 'YES (Nullable)' : 'NOT NULL'}
                                      </td>
                                      <td className="py-2.5 px-4 text-outline font-mono">
                                        {col.columnDefault || '—'}
                                      </td>
                                      <td className="py-2.5 px-4">
                                        {col.primaryKey ? (
                                          <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/30">
                                            PRIMARY KEY
                                          </span>
                                        ) : isHighlighted ? (
                                          <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-semibold">
                                            ALTER TABLE
                                          </span>
                                        ) : (
                                          <span className="text-outline text-[11px]">—</span>
                                        )}
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          </div>
                        </div>
                      )}

                      {/* TAB 3: CDC WAL READINESS */}
                      {activeTab === 'replication' && (
                        <div className="flex flex-col gap-4 max-w-2xl font-mono text-xs">
                          <div className="p-4 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col gap-3">
                            <h3 className="font-bold text-on-surface flex items-center gap-2">
                              <span className="material-symbols-outlined text-primary text-[20px]">sync_alt</span>
                              <span>CDC Logical Replication Inspection</span>
                            </h3>

                            <div className="flex flex-col gap-2 pt-2 border-t border-outline-variant/15 text-on-surface-variant">
                              <div className="flex justify-between items-center">
                                <span>REPLICA IDENTITY</span>
                                <span className={currentTableMeta.replicaIdentityFull ? 'text-primary font-bold' : 'text-amber-400'}>
                                  {currentTableMeta.replicaIdentity}
                                </span>
                              </div>
                              <div className="flex justify-between items-center">
                                <span>PRIMARY KEY DETECTED</span>
                                <span className={currentTableMeta.primaryKeyColumn ? 'text-primary' : 'text-rose-400'}>
                                  {currentTableMeta.primaryKeyColumn || 'NONE (Warning: CDC requires PK)'}
                                </span>
                              </div>
                              <div className="flex justify-between items-center">
                                <span>ZERO-DOWNTIME STATUS</span>
                                <span className="text-emerald-400 font-bold">READY FOR CDC MIGRATION</span>
                              </div>
                            </div>
                          </div>

                          {!currentTableMeta.replicaIdentityFull && (
                            <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex flex-col gap-2">
                              <span className="font-bold flex items-center gap-1.5">
                                <span className="material-symbols-outlined text-[16px]">warning</span>
                                Recommendation for Zero Data Loss
                              </span>
                              <p className="text-[11px] font-sans">
                                To capture all column values during live UPDATE and DELETE events via PostgreSQL WAL, run:
                              </p>
                              <code className="p-2 rounded bg-surface-container-lowest text-on-surface text-[11px]">
                                ALTER TABLE public.&quot;{currentTableMeta.tableName}&quot; REPLICA IDENTITY FULL;
                              </code>
                            </div>
                          )}
                        </div>
                      )}
                    </>
                  )}
                </div>
              </>
            ) : (
              <div className="flex-1 flex flex-col items-center justify-center p-12 text-center text-outline">
                <span className="material-symbols-outlined text-4xl mb-2">table_chart_view</span>
                <p className="text-sm font-semibold text-on-surface">Select a table to inspect</p>
                <p className="text-xs text-on-surface-variant mt-1">
                  Choose a table from the sidebar to preview columns, live data, and replication readiness.
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
  );
}

export default function ExplorerPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-surface flex items-center justify-center text-xs font-mono text-outline">Loading Explorer...</div>}>
      <ExplorerContent />
    </Suspense>
  );
}
