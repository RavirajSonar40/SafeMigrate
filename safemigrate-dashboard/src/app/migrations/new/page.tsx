'use client';

import { useState, useRef, useEffect, Suspense } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { runPreflight, submitMigration } from '@/lib/api';

type PrimitiveType = 'add-column' | 'rename-column' | 'alter-type' | 'add-index' | 'add-constraint' | 'raw-sql';

interface DatabaseOption {
  id: string;
  name: string;
  databaseName: string;
}

function CreateMigrationForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const tableParam = searchParams.get('table');
  const dbParam = searchParams.get('db');

  const [databases, setDatabases] = useState<DatabaseOption[]>([]);
  const [selectedDb, setSelectedDb] = useState<string>(dbParam || 'default-postgres');
  const [availableTables, setAvailableTables] = useState<string[]>([]);

  const [selectedPrimitive, setSelectedPrimitive] = useState<PrimitiveType>('add-column');
  const [tableName, setTableName] = useState<string>(tableParam || 'orders');
  const [columnName, setColumnName] = useState<string>('priority_score');
  const [dataType, setDataType] = useState<string>('INTEGER');
  const [defaultValue, setDefaultValue] = useState<string>('');
  const [isNotNull, setIsNotNull] = useState<boolean>(false);
  const [createIndex, setCreateIndex] = useState<boolean>(false);
  const [rawSql, setRawSql] = useState<string>('');
  const [copied, setCopied] = useState<boolean>(false);
  const [isSimulating, setIsSimulating] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const copyTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Load databases
  useEffect(() => {
    fetch('/api/databases')
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setDatabases(data);
          if (dbParam && data.some((d: DatabaseOption) => d.id === dbParam)) {
            setSelectedDb(dbParam);
          } else {
            setSelectedDb(data[0].id);
          }
        }
      })
      .catch((err) => console.error('Failed to fetch databases:', err));
  }, [dbParam]);

  // Load tables for selected database
  useEffect(() => {
    if (!selectedDb) return;
    fetch(`/api/databases/${selectedDb}/tables`)
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data)) {
          const names = data.map((t: { tableName: string }) => t.tableName);
          setAvailableTables(names);
          if (tableParam && names.includes(tableParam)) {
            setTableName(tableParam);
          } else if (names.length > 0 && !tableParam) {
            setTableName(names[0]);
          }
        }
      })
      .catch((err) => console.error('Failed to load tables for database:', err));
  }, [selectedDb, tableParam]);

  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
    };
  }, []);

  // Dynamic DDL Generation
  const shadowTable = `_sm_shadow_${tableName.replace(/^public\./, '')}`;
  const cleanCol = columnName.trim() || 'column_name';
  const defaultClause = defaultValue.trim() !== '' ? `DEFAULT ${defaultValue.trim()}` : '';
  const notNullClause = isNotNull ? 'NOT NULL' : '';
  const indexName = `idx_${tableName.replace(/^public\./, '')}_${cleanCol}`;

  let generatedDdl = '';
  if (selectedPrimitive === 'raw-sql' && rawSql) {
    generatedDdl = rawSql;
  } else if (selectedPrimitive === 'add-index') {
    generatedDdl = `CREATE INDEX ${indexName} ON ${shadowTable} (${cleanCol});`;
  } else if (selectedPrimitive === 'rename-column') {
    generatedDdl = `ALTER TABLE ${shadowTable} RENAME COLUMN ${cleanCol} TO ${cleanCol}_v2;`;
  } else if (selectedPrimitive === 'alter-type') {
    generatedDdl = `ALTER TABLE ${shadowTable} ALTER COLUMN ${cleanCol} TYPE ${dataType};`;
  } else if (selectedPrimitive === 'add-constraint') {
    generatedDdl = `ALTER TABLE ${shadowTable} ADD CONSTRAINT check_${cleanCol} CHECK (${cleanCol} >= 0) NOT VALID;\nALTER TABLE ${shadowTable} VALIDATE CONSTRAINT check_${cleanCol};`;
  } else {
    // Add Column
    generatedDdl = `-- SafeMigrate generated shadow operation:
ALTER TABLE ${shadowTable} 
  ADD COLUMN ${cleanCol} ${dataType} ${defaultClause} ${notNullClause};`.trim();

    if (createIndex) {
      generatedDdl += `\n\nCREATE INDEX ${indexName} 
  ON ${shadowTable} (${cleanCol});`;
    }
  }

  const handleCopy = () => {
    navigator.clipboard.writeText(generatedDdl);
    setCopied(true);
    if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current);
    copyTimeoutRef.current = setTimeout(() => setCopied(false), 2000);
  };

  const handleLaunch = async () => {
    setErrorMessage(null);

    // Validate NOT NULL without DEFAULT
    if (isNotNull && defaultValue.trim() === '' && selectedPrimitive === 'add-column') {
      setErrorMessage(
        "Adding a NOT NULL column without a DEFAULT value causes table lock failures on tables with data. Please provide a default value (e.g. '0', 'default', or 'active') or uncheck NOT NULL."
      );
      return;
    }

    setIsSimulating(true);
    try {
      const cleanTable = tableName.replace(/^public\./, '').trim();
      // 1. Run live preflight check against Spring Boot
      const preflight = await runPreflight(cleanTable, generatedDdl, selectedDb);
      if (preflight && preflight.errors && preflight.errors.length > 0) {
        setErrorMessage(`Pre-flight safety check failed: ${preflight.errors.join('; ')}`);
        return;
      }
      
      // 2. Submit migration to Spring Boot backend
      const res = await submitMigration({
        tableName: cleanTable,
        ddlStatement: generatedDdl,
        databaseId: selectedDb,
        batchSize: 500
      });

      // 3. Navigate to migration detail cockpit
      router.push(`/migrations/${res.id}`);
    } catch (error: any) {
      console.error('Migration submit failed:', error);
      setErrorMessage(error?.message || 'Failed to submit migration. Please check your inputs.');
    } finally {
      setIsSimulating(false);
    }
  };

  return (
    <div className="flex flex-col w-full max-w-6xl mx-auto py-8 gap-8">
      {/* 4-Step Progress Tracker */}
      <div className="relative py-4 border-b border-outline-variant/10">
        <div className="grid grid-cols-4 gap-4 relative z-10">
          {/* Step 1 */}
          <div className="flex flex-col gap-1 cursor-default">
            <div className="h-1 w-full bg-primary rounded-full"></div>
            <div className="flex items-center justify-between pt-1">
              <span className="font-mono text-xs text-primary flex items-center gap-1 font-medium">
                <span className="material-symbols-outlined text-[15px]">check_circle</span>
                01. Target Table
              </span>
              <span className="text-[10px] font-mono text-on-surface-variant/80 uppercase">Verified</span>
            </div>
            <p className="text-xs text-on-surface truncate">{tableName}</p>
          </div>

          {/* Step 2 */}
          <div className="flex flex-col gap-1">
            <div className="h-1 w-full bg-primary rounded-full relative overflow-hidden">
              <div className="absolute inset-0 bg-white/30 animate-pulse"></div>
            </div>
            <div className="flex items-center justify-between pt-1">
              <span className="font-mono text-xs text-primary font-semibold flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
                02. Define Change
              </span>
              <span className="text-[10px] font-mono text-primary bg-primary/10 px-1.5 py-0.5 rounded border border-primary/20">
                Active
              </span>
            </div>
            <p className="text-xs text-on-surface font-medium truncate">Column & Index Specs</p>
          </div>

          {/* Step 3 */}
          <div className="flex flex-col gap-1 opacity-50">
            <div className="h-1 w-full bg-surface-container-highest rounded-full"></div>
            <div className="flex items-center justify-between pt-1">
              <span className="font-mono text-xs text-on-surface-variant flex items-center gap-1">
                03. Pre-Flight Safety
              </span>
              <span className="text-[10px] font-mono text-outline">Queued</span>
            </div>
            <p className="text-xs text-on-surface-variant truncate">Lock Simulation & SLA</p>
          </div>

          {/* Step 4 */}
          <div className="flex flex-col gap-1 opacity-50">
            <div className="h-1 w-full bg-surface-container-highest rounded-full"></div>
            <div className="flex items-center justify-between pt-1">
              <span className="font-mono text-xs text-on-surface-variant flex items-center gap-1">
                04. Signoff & Launch
              </span>
              <span className="text-[10px] font-mono text-outline">Gated</span>
            </div>
            <p className="text-xs text-on-surface-variant truncate">2-Person Verification</p>
          </div>
        </div>
      </div>

      {/* Error Alert Banner */}
      {errorMessage && (
        <div className="p-4 rounded-xl bg-error/10 border border-error/30 text-error flex items-start gap-3 animate-fade-in shadow-sm">
          <span className="material-symbols-outlined text-[22px] shrink-0 text-error">error</span>
          <div className="flex flex-col gap-1 flex-1">
            <span className="text-xs font-bold uppercase tracking-wider font-mono">Safety / Validation Check</span>
            <p className="text-xs leading-relaxed text-on-surface">{errorMessage}</p>
          </div>
          <button
            type="button"
            onClick={() => setErrorMessage(null)}
            className="text-on-surface-variant hover:text-on-surface text-xs p-1 rounded"
          >
            <span className="material-symbols-outlined text-[16px]">close</span>
          </button>
        </div>
      )}

      {/* Main 2-Column Cockpit Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
        {/* Left Column: Form & Primitive Selection (7 cols) */}
        <div className="lg:col-span-7 flex flex-col gap-6">
          {/* Header Card */}
          <div className="bg-surface-container-low rounded-xl p-6 shadow-sm border border-outline-variant/10">
            <div className="flex items-center justify-between">
              <h1 className="text-xl font-bold tracking-tight text-on-surface">Define Schema Change</h1>
              <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20">
                Zero-Downtime Pipeline
              </span>
            </div>
            <div className="flex items-center flex-wrap gap-2 text-xs text-on-surface-variant pt-3 border-t border-outline-variant/10 mt-2">
              <span className="text-outline font-mono">Target DB:</span>
              <select
                value={selectedDb}
                onChange={(e) => setSelectedDb(e.target.value)}
                className="font-mono text-on-surface bg-surface-container px-2 py-1 rounded border border-outline-variant/30 focus:border-primary outline-none cursor-pointer"
              >
                {databases.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name} ({d.databaseName})
                  </option>
                ))}
              </select>
              <span className="text-outline">·</span>
              <span className="text-outline font-mono">Table:</span>
              {availableTables.length > 0 ? (
                <select
                  value={tableName}
                  onChange={(e) => setTableName(e.target.value)}
                  className="font-mono text-primary font-medium bg-surface-container px-2 py-1 rounded border border-outline-variant/30 focus:border-primary outline-none cursor-pointer"
                >
                  {availableTables.map((t) => (
                    <option key={t} value={t}>public.{t}</option>
                  ))}
                </select>
              ) : (
                <input
                  type="text"
                  value={tableName}
                  onChange={(e) => setTableName(e.target.value)}
                  placeholder="e.g. orders"
                  className="font-mono text-primary font-medium bg-surface-container px-2 py-1 rounded border border-outline-variant/30 focus:border-primary outline-none"
                />
              )}
            </div>
          </div>

          {/* Primitive Selector Matrix */}
          <div className="flex flex-col gap-2">
            <label className="text-[11px] font-mono text-outline uppercase tracking-wider">
              Select Change Primitive
            </label>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {[
                { id: 'add-column', title: 'Add Column', desc: 'Safe append', icon: 'add_box' },
                { id: 'rename-column', title: 'Rename Column', desc: 'View-backed alias', icon: 'drive_file_rename_outline' },
                { id: 'alter-type', title: 'Change Type', desc: 'Dual-write sync', icon: 'transform' },
                { id: 'add-index', title: 'Concurrent Index', desc: 'Non-blocking B-Tree', icon: 'bolt' },
                { id: 'add-constraint', title: 'Add Constraint', desc: 'NOT VALID & validate', icon: 'rule' },
                { id: 'raw-sql', title: 'Raw ALTER Script', desc: 'Custom DDL AST', icon: 'terminal' },
              ].map((prim) => {
                const isSelected = selectedPrimitive === prim.id;
                return (
                  <div
                    key={prim.id}
                    onClick={() => setSelectedPrimitive(prim.id as PrimitiveType)}
                    className={`rounded-lg p-3.5 cursor-pointer transition-all flex flex-col justify-between h-24 relative overflow-hidden group border ${
                      isSelected
                        ? 'bg-surface-container-high border-primary/50 shadow-sm'
                        : 'bg-surface-container-low border-outline-variant/10 hover:bg-surface-container hover:border-outline-variant/30'
                    }`}
                  >
                    {isSelected && <div className="absolute top-0 left-0 bottom-0 w-1 bg-primary"></div>}
                    <div className="flex items-center justify-between">
                      <span className={`material-symbols-outlined text-[20px] ${isSelected ? 'text-primary' : 'text-outline group-hover:text-on-surface'}`}>
                        {prim.icon}
                      </span>
                      {isSelected && (
                        <span className="material-symbols-outlined text-[16px] text-primary">check_circle</span>
                      )}
                    </div>
                    <div>
                      <div className={`text-xs font-semibold ${isSelected ? 'text-primary' : 'text-on-surface'}`}>
                        {prim.title}
                      </div>
                      <div className="text-[11px] font-mono text-outline">{prim.desc}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Configuration Form */}
          <div className="bg-surface-container-low rounded-xl p-6 shadow-sm flex flex-col gap-5 border border-outline-variant/10">
            <div className="flex items-center justify-between pb-2 border-b border-outline-variant/10">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-primary"></span>
                <h2 className="text-sm font-semibold text-on-surface">Column Configuration</h2>
              </div>
              <span className="text-xs font-mono text-outline">Target: {tableName}</span>
            </div>

            {selectedPrimitive === 'raw-sql' ? (
              <div className="flex flex-col gap-1.5">
                <label className="text-[11px] font-mono text-on-surface-variant uppercase flex justify-between">
                  <span>Custom DDL Statement</span>
                  <span className="text-primary lowercase">Postgres AST Validated</span>
                </label>
                <textarea
                  rows={4}
                  value={rawSql}
                  onChange={(e) => setRawSql(e.target.value)}
                  placeholder="ALTER TABLE orders ADD COLUMN ..."
                  className="w-full bg-surface-container-lowest text-on-surface font-mono text-xs p-3 rounded border border-outline-variant/30 focus:border-primary focus:outline-none"
                />
              </div>
            ) : (
              <>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {/* Column Name */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[11px] font-mono text-on-surface-variant uppercase flex items-center justify-between">
                      <span>Column Identifier</span>
                      <span className="text-primary lowercase font-mono">required</span>
                    </label>
                    <div className="relative flex items-center">
                      <span className="material-symbols-outlined text-[16px] text-outline absolute left-3 pointer-events-none">
                        tag
                      </span>
                      <input
                        type="text"
                        value={columnName}
                        onChange={(e) => setColumnName(e.target.value)}
                        placeholder="e.g. priority_score"
                        className="w-full bg-surface-container-lowest text-on-surface font-mono text-xs pl-9 pr-3 py-2 rounded border border-outline-variant/30 focus:border-primary focus:outline-none"
                      />
                    </div>
                    <p className="text-[11px] font-mono text-outline">Snake_case recommended for PostgreSQL</p>
                  </div>

                  {/* PostgreSQL Type */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[11px] font-mono text-on-surface-variant uppercase flex items-center justify-between">
                      <span>PostgreSQL Type</span>
                      <span className="text-outline">canonical</span>
                    </label>
                    <div className="relative flex items-center">
                      <select
                        value={dataType}
                        onChange={(e) => setDataType(e.target.value)}
                        className="w-full bg-surface-container-lowest text-on-surface font-mono text-xs px-3 py-2 rounded border border-outline-variant/30 focus:border-primary focus:outline-none appearance-none cursor-pointer"
                      >
                        <option value="INTEGER">INTEGER (int4)</option>
                        <option value="BIGINT">BIGINT (int8)</option>
                        <option value="VARCHAR(255)">VARCHAR(255)</option>
                        <option value="TEXT">TEXT</option>
                        <option value="BOOLEAN">BOOLEAN</option>
                        <option value="TIMESTAMPTZ">TIMESTAMPTZ (with tz)</option>
                        <option value="JSONB">JSONB (binary json)</option>
                        <option value="NUMERIC(12,2)">NUMERIC(12,2)</option>
                      </select>
                      <span className="material-symbols-outlined text-[18px] text-outline absolute right-3 pointer-events-none">
                        unfold_more
                      </span>
                    </div>
                    <p className="text-[11px] font-mono text-outline">Native type with automated cast support</p>
                  </div>
                </div>

                {/* Default Expression */}
                <div className="flex flex-col gap-1.5">
                  <label className="text-[11px] font-mono text-on-surface-variant uppercase flex items-center justify-between">
                    <span>Default Expression</span>
                    <span className="text-outline">evaluated at backfill</span>
                  </label>
                  <div className="relative flex items-center">
                    <span className="material-symbols-outlined text-[16px] text-outline absolute left-3 pointer-events-none">
                      code
                    </span>
                    <input
                      type="text"
                      value={defaultValue}
                      onChange={(e) => setDefaultValue(e.target.value)}
                      placeholder="e.g. 0, NOW(), 'active'"
                      className="w-full bg-surface-container-lowest text-on-surface font-mono text-xs pl-9 pr-3 py-2 rounded border border-outline-variant/30 focus:border-primary focus:outline-none"
                    />
                  </div>
                  <p className="text-xs text-on-surface-variant">
                    Constant defaults in PostgreSQL 11+ are metadata-only rewrites without full table rewrites.
                  </p>
                </div>

                {/* Safety Guardrail Checkboxes */}
                <div className="flex flex-col gap-3 pt-2">
                  <label className="text-[11px] font-mono text-outline uppercase tracking-wider">
                    Automated Safety Constraints
                  </label>

                  {/* NOT NULL */}
                  <div
                    onClick={() => setIsNotNull(!isNotNull)}
                    className="bg-surface-container rounded-lg p-3.5 flex items-start gap-3 hover:bg-surface-container-high transition-colors cursor-pointer border border-outline-variant/10"
                  >
                    <input
                      type="checkbox"
                      checked={isNotNull}
                      onChange={() => {}}
                      className="mt-0.5 w-4 h-4 rounded text-primary accent-primary cursor-pointer"
                    />
                    <div className="flex flex-col gap-0.5">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-on-surface">Enforce NOT NULL constraint</span>
                        <span className="text-[10px] font-mono px-1.5 py-0.2 bg-primary/10 text-primary rounded border border-primary/20">
                          Safe Backfill
                        </span>
                      </div>
                      <p className="text-xs text-on-surface-variant">
                        Safe backfill enabled: automatically populated with default value before constraint enforcement.
                      </p>
                    </div>
                  </div>

                  {/* Concurrent Index */}
                  <div
                    onClick={() => setCreateIndex(!createIndex)}
                    className="bg-surface-container rounded-lg p-3.5 flex items-start gap-3 hover:bg-surface-container-high transition-colors cursor-pointer border border-outline-variant/10"
                  >
                    <input
                      type="checkbox"
                      checked={createIndex}
                      onChange={() => {}}
                      className="mt-0.5 w-4 h-4 rounded text-primary accent-primary cursor-pointer"
                    />
                    <div className="flex flex-col gap-0.5">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-on-surface">Create Concurrent B-Tree Index</span>
                        <span className="text-xs font-mono text-primary">{indexName}</span>
                      </div>
                      <p className="text-xs text-on-surface-variant">
                        Executes via <code className="text-on-surface font-mono">CREATE INDEX CONCURRENTLY</code> without taking an <code className="text-tertiary font-mono">ACCESS EXCLUSIVE</code> lock.
                      </p>
                    </div>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>

        {/* Right Column: Live DDL Inspector & Impact Projection (5 cols) */}
        <div className="lg:col-span-5 flex flex-col gap-5 sticky top-20">
          {/* Safe DDL Preview Box */}
          <div className="bg-surface-container-low rounded-xl p-6 shadow-sm flex flex-col gap-4 border border-outline-variant/10">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-[18px] text-primary">terminal</span>
                <h3 className="text-sm font-semibold text-on-surface">Target DDL Preview</h3>
              </div>
              <button
                type="button"
                onClick={handleCopy}
                className="flex items-center gap-1 text-xs font-mono text-outline hover:text-on-surface transition-colors cursor-pointer"
              >
                <span className="material-symbols-outlined text-[15px]">
                  {copied ? 'done' : 'content_copy'}
                </span>
                <span>{copied ? 'Copied' : 'Copy'}</span>
              </button>
            </div>
            <p className="text-xs text-on-surface-variant">
              Generated script runs on the isolated shadow replica partition before zero-lock switchover.
            </p>

            <div className="relative bg-surface-container-lowest rounded-lg p-4 font-mono text-xs overflow-x-auto border border-outline-variant/20">
              <div className="flex items-center justify-between pb-2 mb-2 border-b border-outline-variant/10 text-[11px]">
                <span className="font-mono text-outline uppercase tracking-wider">PG-SAFE-TRANSACTION-PLAN</span>
                <span className="text-primary flex items-center gap-1 font-mono">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary inline-block"></span>
                  Syntax Valid
                </span>
              </div>
              <pre className="text-on-surface leading-relaxed whitespace-pre-wrap selection:bg-primary/30">
                {generatedDdl}
              </pre>
            </div>

            <div className="flex items-center justify-between text-outline text-xs font-mono pt-1">
              <span className="flex items-center gap-1">
                <span className="material-symbols-outlined text-[16px] text-secondary">verified</span>
                AST Sanitized
              </span>
              <span>Lock Mode: ROW EXCLUSIVE</span>
            </div>
          </div>

          {/* Impact Projection Card */}
          <div className="bg-surface-container-low rounded-xl p-6 shadow-sm flex flex-col gap-4 border border-outline-variant/10">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-[18px] text-primary">speed</span>
                <h4 className="text-sm font-semibold text-on-surface">Impact Projection</h4>
              </div>
              <span className="text-[10px] font-mono text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                Optimal
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="bg-surface-container p-3 rounded flex flex-col gap-1 border border-outline-variant/10">
                <span className="text-[10px] font-mono uppercase text-outline">Est. Duration</span>
                <span className="text-sm font-mono font-semibold text-on-surface">~ 18.4 seconds</span>
                <div className="w-full bg-surface-container-lowest h-1 rounded-full mt-1 overflow-hidden">
                  <div className="bg-primary h-full rounded-full" style={{ width: '25%' }}></div>
                </div>
              </div>
              <div className="bg-surface-container p-3 rounded flex flex-col gap-1 border border-outline-variant/10">
                <span className="text-[10px] font-mono uppercase text-outline">Peak Lock Latency</span>
                <span className="text-sm font-mono font-semibold text-primary">&lt; 4 ms</span>
                <div className="w-full bg-surface-container-lowest h-1 rounded-full mt-1 overflow-hidden">
                  <div className="bg-primary h-full rounded-full" style={{ width: '8%' }}></div>
                </div>
              </div>
            </div>

            {/* Lock Latency SVG Sparkline */}
            <div className="flex flex-col gap-1">
              <div className="flex items-center justify-between text-[11px] font-mono text-outline">
                <span>Simulated I/O overhead</span>
                <span className="text-primary font-medium">+1.8% Write Overhead</span>
              </div>
              <div className="w-full h-12 bg-surface-container-lowest rounded p-1 flex items-end border border-outline-variant/20">
                <svg className="w-full h-10 text-primary overflow-visible" fill="none" preserveAspectRatio="none" viewBox="0 0 300 40">
                  <path d="M0,35 L40,35 L70,34 L110,35 L140,28 L160,18 L170,12 L180,24 L200,32 L250,35 L300,35" stroke="currentColor" strokeWidth="1.75" vectorEffect="non-scaling-stroke"></path>
                  <path d="M0,35 L40,35 L70,34 L110,35 L140,28 L160,18 L170,12 L180,24 L200,32 L250,35 L300,35 L300,40 L0,40 Z" fill="currentColor" fillOpacity="0.08"></path>
                  <circle cx="170" cy="12" r="3" fill="#4edea3" className="animate-pulse"></circle>
                </svg>
              </div>
            </div>

            <div className="bg-surface-container/60 p-3 rounded flex items-start gap-2 text-on-surface-variant text-xs border border-outline-variant/10">
              <span className="material-symbols-outlined text-[16px] text-primary shrink-0 mt-0.5">shield_with_heart</span>
              <span>SafeMigrate throttle active: Worker auto-pauses backfill if replica lag surpasses 250ms.</span>
            </div>
          </div>
        </div>
      </div>

      {/* Bottom Navigation Action Bar */}
      <div className="pt-4 flex flex-col sm:flex-row items-center justify-between gap-4 border-t border-outline-variant/10">
        <div className="flex items-center gap-3 w-full sm:w-auto">
          <Link
            href="/overview"
            className="flex items-center justify-center gap-2 px-4 h-9 rounded bg-surface-container-low text-on-surface hover:bg-surface-container transition-colors text-xs font-medium shadow-sm border border-outline-variant/20"
          >
            <span className="material-symbols-outlined text-[16px] text-outline">arrow_back</span>
            <span>Back to Dashboard</span>
          </Link>
          <button
            type="button"
            className="flex items-center justify-center gap-1.5 px-4 h-9 rounded bg-surface-container-low text-on-surface-variant hover:text-on-surface hover:bg-surface-container transition-colors text-xs font-medium shadow-sm border border-outline-variant/20"
          >
            <span className="material-symbols-outlined text-[16px]">bookmark_border</span>
            <span>Save as Draft</span>
          </button>
        </div>

        <div className="flex items-center gap-4 w-full sm:w-auto justify-end">
          <div className="hidden md:flex flex-col text-right">
            <span className="text-xs text-on-surface font-medium">Ready for Simulation</span>
            <span className="text-[11px] font-mono text-outline">Target Lock Ceiling: 250ms</span>
          </div>
          <button
            type="button"
            onClick={handleLaunch}
            disabled={isSimulating}
            className="flex items-center justify-center gap-2 px-6 h-9 rounded bg-primary text-surface-container-lowest font-semibold text-xs hover:bg-primary-fixed transition-colors shadow-md cursor-pointer disabled:opacity-50"
          >
            {isSimulating ? (
              <>
                <span className="material-symbols-outlined text-[18px] animate-spin">sync</span>
                <span>Running Pre-Flight Checks...</span>
              </>
            ) : (
              <>
                <span>Launch Zero-Downtime Migration</span>
                <span className="material-symbols-outlined text-[18px]">arrow_forward</span>
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function CreateMigrationPage() {
  return (
    <Suspense fallback={<div className="p-12 text-center text-xs font-mono text-outline">Loading Migration Wizard...</div>}>
      <CreateMigrationForm />
    </Suspense>
  );
}
