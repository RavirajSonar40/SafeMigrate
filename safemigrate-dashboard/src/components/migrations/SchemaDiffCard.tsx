'use client';

import { useMemo } from 'react';

interface ColumnDiff {
  name: string;
  sourceType: string;
  targetType: string;
  status: 'UNCHANGED' | 'MODIFIED' | 'ADDED' | 'DROPPED';
  nullable: boolean;
  defaultValue?: string;
  isPrimaryKey?: boolean;
}

interface SchemaDiffCardProps {
  tableName: string;
  shadowTableName?: string;
  ddlStatement?: string;
  auditedColumns?: string[];
}

export default function SchemaDiffCard({ tableName, shadowTableName, ddlStatement, auditedColumns }: SchemaDiffCardProps) {
  // Infer column diffs from DDL and known schema columns
  const diffs: ColumnDiff[] = useMemo(() => {
    const list: ColumnDiff[] = [];
    const ddl = ddlStatement || '';

    // Check for ALTER COLUMN TYPE
    const alterTypeMatches = [...ddl.matchAll(/ALTER\s+COLUMN\s+([a-zA-Z0-9_]+)\s+TYPE\s+([a-zA-Z0-9_(),\s]+?)(?:;|$)/gi)];
    // Check for ADD COLUMN
    const addColMatches = [...ddl.matchAll(/ADD\s+COLUMN(?:\s+IF\s+NOT\s+EXISTS)?\s+([a-zA-Z0-9_]+)\s+([a-zA-Z0-9_(),\s]+?)(?:\s+DEFAULT\s+([^;]+))?(?:\s+NOT\s+NULL)?(?:;|$)/gi)];

    // Known base columns for merchants or orders
    const baseCols = tableName.includes('merchant')
      ? [
          { name: 'id', type: 'BIGINT', pk: true },
          { name: 'merchant_code', type: 'VARCHAR(64)' },
          { name: 'business_name', type: 'VARCHAR(128)' },
          { name: 'category', type: 'VARCHAR(64)' },
          { name: 'rating', type: 'NUMERIC(3,2)' },
          { name: 'commission_rate', type: 'NUMERIC(5,4)' },
          { name: 'is_active', type: 'BOOLEAN' },
          { name: 'country_code', type: 'VARCHAR(4)' },
          { name: 'created_at', type: 'TIMESTAMPTZ' }
        ]
      : [
          { name: 'id', type: 'BIGINT', pk: true },
          { name: 'user_id', type: 'BIGINT' },
          { name: 'merchant_id', type: 'BIGINT' },
          { name: 'amount', type: 'NUMERIC(10,2)' },
          { name: 'currency', type: 'VARCHAR(4)' },
          { name: 'status', type: 'VARCHAR(32)' },
          { name: 'created_at', type: 'TIMESTAMPTZ' },
          { name: 'updated_at', type: 'TIMESTAMPTZ' }
        ];

    // Populate base columns
    for (const b of baseCols) {
      let status: ColumnDiff['status'] = 'UNCHANGED';
      let targetType = b.type;

      // Did this column get altered in DDL?
      const alter = alterTypeMatches.find(m => m[1].toLowerCase() === b.name.toLowerCase());
      if (alter) {
        status = 'MODIFIED';
        targetType = alter[2].trim().toUpperCase();
      }

      list.push({
        name: b.name,
        sourceType: b.type,
        targetType: targetType,
        status: status,
        nullable: !b.pk,
        isPrimaryKey: b.pk
      });
    }

    // Add new columns added in DDL
    for (const add of addColMatches) {
      const colName = add[1].toLowerCase();
      if (!list.some(c => c.name.toLowerCase() === colName)) {
        list.push({
          name: colName,
          sourceType: '— (NEW)',
          targetType: add[2].trim().toUpperCase(),
          status: 'ADDED',
          nullable: !add[0].toUpperCase().includes('NOT NULL'),
          defaultValue: add[3]?.trim()
        });
      }
    }

    return list;
  }, [tableName, ddlStatement]);

  const modifiedCount = diffs.filter(d => d.status === 'MODIFIED').length;
  const addedCount = diffs.filter(d => d.status === 'ADDED').length;
  const unchangedCount = diffs.filter(d => d.status === 'UNCHANGED').length;

  return (
    <div className="bg-surface-container border border-outline-variant/30 rounded-2xl p-6 flex flex-col gap-5 shadow-sm">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-outline-variant/15">
        <div>
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-[20px]">difference</span>
            <h3 className="text-base font-bold text-on-surface">Schema Evolution Diff</h3>
          </div>
          <p className="text-xs text-on-surface-variant mt-0.5">
            Zero-downtime projection from current relation <span className="font-mono text-on-surface font-semibold">{tableName}</span> to shadow schema
          </p>
        </div>

        <div className="flex items-center gap-2">
          {modifiedCount > 0 && (
            <span className="px-2.5 py-1 rounded-lg text-xs font-mono font-semibold bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              {modifiedCount} Modified
            </span>
          )}
          {addedCount > 0 && (
            <span className="px-2.5 py-1 rounded-lg text-xs font-mono font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              +{addedCount} Added
            </span>
          )}
          <span className="px-2.5 py-1 rounded-lg text-xs font-mono bg-surface-container-high text-on-surface-variant border border-outline-variant/20">
            {unchangedCount} Unchanged
          </span>
        </div>
      </div>

      {/* Diff Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-outline-variant/20 text-on-surface-variant font-mono uppercase text-[10px]">
              <th className="pb-3 font-semibold">Column</th>
              <th className="pb-3 font-semibold">Current Schema</th>
              <th className="pb-3 text-center font-semibold">Evolution</th>
              <th className="pb-3 font-semibold">Target Schema</th>
              <th className="pb-3 font-semibold">Constraints</th>
              <th className="pb-3 text-right font-semibold">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-outline-variant/10 font-mono">
            {diffs.map((col) => (
              <tr 
                key={col.name}
                className={`transition-colors ${
                  col.status === 'MODIFIED' ? 'bg-cyan-500/5 hover:bg-cyan-500/10' :
                  col.status === 'ADDED' ? 'bg-emerald-500/5 hover:bg-emerald-500/10' :
                  'hover:bg-surface-container-high/40'
                }`}
              >
                {/* Column Name */}
                <td className="py-3 font-semibold text-on-surface flex items-center gap-2">
                  {col.isPrimaryKey && (
                    <span className="material-symbols-outlined text-[14px] text-amber-400" title="Primary Key">key</span>
                  )}
                  <span>{col.name}</span>
                </td>

                {/* Current Type */}
                <td className="py-3 text-on-surface-variant">
                  <span className={col.status === 'ADDED' ? 'text-outline/40 italic' : 'text-on-surface-variant'}>
                    {col.sourceType}
                  </span>
                </td>

                {/* Evolution Arrow */}
                <td className="py-3 text-center">
                  {col.status === 'MODIFIED' ? (
                    <span className="material-symbols-outlined text-cyan-400 text-[16px] animate-pulse">arrow_forward</span>
                  ) : col.status === 'ADDED' ? (
                    <span className="text-emerald-400 font-bold text-sm">+</span>
                  ) : (
                    <span className="text-outline/30">=</span>
                  )}
                </td>

                {/* Target Type */}
                <td className="py-3">
                  <span className={
                    col.status === 'MODIFIED' ? 'text-cyan-400 font-bold' :
                    col.status === 'ADDED' ? 'text-emerald-400 font-bold' :
                    'text-on-surface-variant'
                  }>
                    {col.targetType}
                  </span>
                </td>

                {/* Constraints */}
                <td className="py-3 text-[11px] text-on-surface-variant">
                  <div className="flex items-center gap-1.5 flex-wrap">
                    {col.isPrimaryKey && (
                      <span className="px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-300 border border-amber-500/20 text-[10px]">
                        PRIMARY KEY
                      </span>
                    )}
                    {!col.nullable && !col.isPrimaryKey && (
                      <span className="px-1.5 py-0.5 rounded bg-surface-container-highest text-on-surface text-[10px]">
                        NOT NULL
                      </span>
                    )}
                    {col.defaultValue && (
                      <span className="px-1.5 py-0.5 rounded bg-surface-container-highest text-primary text-[10px] truncate max-w-[140px]">
                        DEF: {col.defaultValue}
                      </span>
                    )}
                  </div>
                </td>

                {/* Status Badge */}
                <td className="py-3 text-right">
                  {col.status === 'MODIFIED' && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-cyan-500/15 text-cyan-400 border border-cyan-500/30">
                      MODIFIED
                    </span>
                  )}
                  {col.status === 'ADDED' && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                      ADDED
                    </span>
                  )}
                  {col.status === 'UNCHANGED' && (
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] text-on-surface-variant/70">
                      UNCHANGED
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
