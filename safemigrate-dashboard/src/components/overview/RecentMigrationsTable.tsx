'use client';

import Link from 'next/link';
import { MigrationResponse } from '@/lib/types';

interface RecentMigrationsTableProps {
  migrations: MigrationResponse[];
}

export default function RecentMigrationsTable({ migrations }: RecentMigrationsTableProps) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-on-surface tracking-tight">Recent Migrations</h2>
        <span className="text-xs text-primary flex items-center gap-1 font-medium cursor-pointer hover:underline">
          <span>View all history</span>
          <span className="material-symbols-outlined text-[16px]">chevron_right</span>
        </span>
      </div>

      <div className="w-full rounded-xl bg-surface-container-low shadow-sm overflow-hidden border border-outline-variant/10">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs whitespace-nowrap">
            <thead>
              <tr className="bg-surface-container text-on-surface-variant font-mono uppercase tracking-wider text-[11px] border-b border-outline-variant/20">
                <th className="py-3 px-6" scope="col">Migration ID</th>
                <th className="py-3 px-4" scope="col">Database</th>
                <th className="py-3 px-4" scope="col">Table</th>
                <th className="py-3 px-4" scope="col">Schema Change</th>
                <th className="py-3 px-4" scope="col">Status</th>
                <th className="py-3 px-4" scope="col">Duration</th>
                <th className="py-3 px-4" scope="col">Started By</th>
                <th className="py-3 px-6 text-right" scope="col">Date</th>
              </tr>
            </thead>
            <tbody className="text-on-surface divide-y divide-outline-variant/10 font-mono-numbers">
              {migrations.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-8 text-center text-on-surface-variant font-mono text-xs">
                    No historical migrations found in target database. Click &quot;New Migration&quot; above to execute your first schema alteration.
                  </td>
                </tr>
              ) : (
                migrations.map((m) => {
                  const isCompleted = m.state === 'COMPLETED';
                  const isBackfilling = m.state === 'BACKFILLING';
                  const isReady = m.state === 'READY_FOR_CUTOVER' || m.state === 'READY_CUTOVER';
                  const isRolledBack = m.state === 'ROLLED_BACK';

                  const dateFormatted = m.createdAt
                    ? new Date(m.createdAt).toLocaleString([], {
                        month: 'short',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })
                    : 'Just now';

                  return (
                    <tr key={m.id} className="hover:bg-surface-container transition-colors">
                      <td className="py-3 px-6 font-mono font-medium text-primary">
                        <Link href={`/migrations/${m.id}`} className="hover:underline">
                          #{m.id}
                        </Link>
                      </td>
                      <td className="py-3 px-4 text-on-surface-variant font-mono">
                        {m.database || 'production-db-us-east'}
                      </td>
                      <td className="py-3 px-4 font-sans font-medium text-on-surface">
                        {m.tableName}
                      </td>
                      <td className="py-3 px-4 font-mono text-on-surface-variant truncate max-w-xs">
                        {m.ddlStatement}
                      </td>
                      <td className="py-3 px-4">
                        {isCompleted && (
                          <span className="inline-flex items-center gap-1 font-mono text-[10px] px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20 uppercase font-semibold">
                            <span className="material-symbols-outlined text-[12px]">check</span> COMPLETED
                          </span>
                        )}
                        {isBackfilling && (
                          <span className="inline-flex items-center gap-1 font-mono text-[10px] px-2 py-0.5 rounded bg-surface-container text-primary border border-primary/20 uppercase font-semibold">
                            <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span> BACKFILLING
                          </span>
                        )}
                        {isReady && (
                          <span className="inline-flex items-center gap-1 font-mono text-[10px] px-2 py-0.5 rounded bg-secondary/10 text-secondary border border-secondary/20 uppercase font-semibold">
                            READY
                          </span>
                        )}
                        {isRolledBack && (
                          <span className="inline-flex items-center gap-1 font-mono text-[10px] px-2 py-0.5 rounded bg-error/10 text-error border border-error/20 uppercase font-semibold">
                            ROLLED BACK
                          </span>
                        )}
                      </td>
                      <td className="py-3 px-4 text-on-surface-variant font-mono">
                        {m.cutoverDurationMs ? `${m.cutoverDurationMs}ms hold` : isCompleted ? 'Completed' : 'Active'}
                      </td>
                      <td className="py-3 px-4 text-on-surface-variant font-sans">
                        {m.initiatedBy || 'sre-operator@safemigrate.io'}
                      </td>
                      <td className="py-3 px-6 text-right text-on-surface-variant font-mono">
                        {dateFormatted}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}
