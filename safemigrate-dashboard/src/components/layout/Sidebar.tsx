'use client';

import { useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';

export default function Sidebar() {
  const pathname = usePathname();
  const { user } = useAuth();
  const [activeModal, setActiveModal] = useState<string | null>(null);

  // Settings form state
  const [batchSize, setBatchSize] = useState('500');
  const [throttleMs, setThrottleMs] = useState('20');
  const [lockTimeoutMs, setLockTimeoutMs] = useState('2000');
  const [settingsSaved, setSettingsSaved] = useState(false);

  const handleSaveSettings = () => {
    setSettingsSaved(true);
    setTimeout(() => setSettingsSaved(false), 2000);
  };

  const navItems = [
    { label: 'Overview', href: '/overview', icon: 'dashboard', isLink: true },
    { label: 'Migrations', href: '/overview#migrations', icon: 'move_up', isLink: true },
    { label: 'Databases', href: '/databases', icon: 'database', isLink: true },
    { label: 'DB Explorer', href: '/explorer', icon: 'table_chart', isLink: true },
    { label: 'Workers', href: '/operations', icon: 'sync_alt', isLink: true },
    { label: 'Activity & Audit', href: '/activity', icon: 'timeline', isLink: true },
    { label: 'Approvals', modalKey: 'approvals', icon: 'verified_user', badge: '1', isLink: false },
    { label: 'Settings', modalKey: 'settings', icon: 'settings', isLink: false },
  ];

  return (
    <>
      <aside className="fixed left-0 top-0 h-full w-[220px] bg-surface-container-low border-r border-outline-variant/30 z-50 flex flex-col justify-between select-none">
        <div className="flex flex-col">
          {/* Brand Header */}
          <Link href="/overview" className="h-14 px-4 flex items-center gap-2.5 border-b border-outline-variant/20 hover:bg-surface-container transition-colors">
            <div className="w-8 h-8 rounded-lg bg-primary/10 border border-primary/30 flex items-center justify-center text-primary shadow-sm">
              <span className="material-symbols-outlined text-[20px]">dataset</span>
            </div>
            <div className="flex flex-col">
              <span className="font-bold text-base tracking-tight text-on-surface leading-tight">SafeMigrate</span>
              <span className="text-[10px] font-mono text-primary leading-none">v2.4 Engine</span>
            </div>
          </Link>

          {/* Navigation Items */}
          <nav className="flex flex-col py-3 gap-1 px-2">
            {navItems.map((item) => {
              const isActive = item.isLink && (pathname === item.href || (item.href === '/overview' && pathname === '/'));

              if (item.isLink) {
                return (
                  <Link
                    key={item.label}
                    href={item.href!}
                    className={`flex items-center justify-between px-3 py-2 rounded-lg text-xs font-medium transition-all ${
                      isActive
                        ? 'bg-surface-container text-primary font-semibold border border-primary/20 shadow-sm'
                        : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                    }`}
                  >
                    <div className="flex items-center gap-2.5">
                      <span className="material-symbols-outlined text-[18px] shrink-0">{item.icon}</span>
                      <span>{item.label}</span>
                    </div>
                    {item.badge && (
                      <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-primary/10 text-primary border border-primary/30">
                        {item.badge}
                      </span>
                    )}
                  </Link>
                );
              }

              return (
                <button
                  key={item.label}
                  onClick={() => setActiveModal(item.modalKey!)}
                  className={`flex items-center justify-between px-3 py-2 rounded-lg text-xs font-medium transition-all cursor-pointer text-left ${
                    activeModal === item.modalKey
                      ? 'bg-surface-container text-primary font-semibold border border-primary/20 shadow-sm'
                      : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                  }`}
                >
                  <div className="flex items-center gap-2.5">
                    <span className="material-symbols-outlined text-[18px] shrink-0">{item.icon}</span>
                    <span>{item.label}</span>
                  </div>
                  {item.badge && (
                    <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-primary/10 text-primary border border-primary/30">
                      {item.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>
        </div>

        {/* Footer Engine Status */}
        <div className="p-3 border-t border-outline-variant/20 bg-surface-container-lowest/60 flex flex-col gap-2">
          <div className="flex items-center justify-between text-on-surface-variant text-xs font-mono">
            <span className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-primary inline-block animate-pulse"></span>
              <span>Loom Virtual I/O</span>
            </span>
            <span className="text-[10px] text-primary font-bold px-1.5 py-0.5 rounded bg-primary/10 border border-primary/20">
              HEALTHY
            </span>
          </div>
          <a
            href="https://github.com/RavirajSonar40/SafeMigrate/actions"
            target="_blank"
            rel="noreferrer"
            className="text-[10px] font-mono text-outline hover:text-primary transition-colors flex items-center justify-between border-t border-outline-variant/10 pt-1.5"
          >
            <span>Live CI/CD Logs</span>
            <span className="material-symbols-outlined text-[13px]">open_in_new</span>
          </a>
        </div>
      </aside>

      {/* --- SIDEBAR MODALS --- */}

      {/* 1. Databases Modal */}
      {activeModal === 'databases' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-2xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">database</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Connected PostgreSQL Targets</h3>
                  <p className="text-xs text-on-surface-variant">Active logical replication topologies</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-3">
              <div className="p-4 rounded-xl bg-surface-container-low border border-primary/30 flex flex-col gap-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <span className="w-2.5 h-2.5 rounded-full bg-primary animate-pulse"></span>
                    <span className="font-mono font-bold text-sm text-on-surface">safemigrate_test (Docker / Primary)</span>
                  </div>
                  <span className="text-[10px] font-mono bg-primary/10 text-primary px-2 py-0.5 rounded border border-primary/20">
                    ACTIVE PUBLISHER
                  </span>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono pt-2 border-t border-outline-variant/15 text-on-surface-variant">
                  <div>
                    <span className="text-[10px] text-outline block">HOST : PORT</span>
                    <span className="text-on-surface font-semibold">localhost:5432</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-outline block">WAL LEVEL</span>
                    <span className="text-primary font-semibold">logical</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-outline block">SLOTS ACTIVE</span>
                    <span className="text-on-surface font-semibold">1 (safemigrate_slot)</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-outline block">CDC LAG</span>
                    <span className="text-primary font-semibold">0 ms Baseline</span>
                  </div>
                </div>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-medium text-on-surface transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 2. Tables Modal */}
      {activeModal === 'tables' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-3xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">table_chart</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Target Tables &amp; Shadow Tables</h3>
                  <p className="text-xs text-on-surface-variant">Live schema states in database safemigrate_test</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-3 max-h-96 overflow-y-auto">
              {/* orders table */}
              <div className="p-4 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-mono font-bold text-sm text-on-surface">public.orders</span>
                    <span className="text-[10px] font-mono text-outline uppercase">(Active Source)</span>
                  </div>
                  <span className="text-xs font-mono font-semibold text-primary">1,466 Rows</span>
                </div>
                <div className="text-xs font-mono text-on-surface-variant flex flex-wrap gap-2 pt-2 border-t border-outline-variant/10">
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">PK: id (bigserial)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">customer_id (varchar)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">amount (numeric)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">status (varchar)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">Replica Identity: FULL</span>
                </div>
              </div>

              {/* orders__shadow table */}
              <div className="p-4 rounded-xl bg-surface-container-low border border-primary/30 flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-mono font-bold text-sm text-primary">public.orders__shadow</span>
                    <span className="text-[10px] font-mono text-primary uppercase">(Shadow Target)</span>
                  </div>
                  <span className="text-xs font-mono font-semibold text-primary">1,466 Rows (100% Synced)</span>
                </div>
                <div className="text-xs font-mono text-on-surface-variant flex flex-wrap gap-2 pt-2 border-t border-outline-variant/10">
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">PK: id (bigserial)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-primary/30 text-primary">+ priority_score (integer default 0)</span>
                  <span className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/20">Status: In Sync</span>
                </div>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-medium text-on-surface transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 3. Workers / Pod Fleet Modal */}
      {activeModal === 'workers' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-2xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">sync_alt</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Migration Workers &amp; Pod Fleet</h3>
                  <p className="text-xs text-on-surface-variant">Distributed chunk processors &amp; state coordinators</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-3">
              <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className="w-2.5 h-2.5 rounded-full bg-primary animate-pulse"></span>
                  <div>
                    <div className="text-xs font-mono font-bold text-on-surface">safemigrate_worker_0</div>
                    <div className="text-[11px] text-on-surface-variant">Backfill range: Chunk [0..1,000,000] · Distributed Lock: Leased</div>
                  </div>
                </div>
                <span className="text-xs font-mono text-primary font-bold">9,420 r/s</span>
              </div>

              <div className="p-3.5 rounded-xl bg-surface-container-low border border-outline-variant/20 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className="w-2.5 h-2.5 rounded-full bg-primary animate-pulse"></span>
                  <div>
                    <div className="text-xs font-mono font-bold text-on-surface">safemigrate_server</div>
                    <div className="text-[11px] text-on-surface-variant">Spring Boot 3 Control Plane &amp; SSE Coordinator · Port 8080</div>
                  </div>
                </div>
                <span className="text-xs font-mono text-primary font-bold">HEALTHY</span>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-medium text-on-surface transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 4. Events Modal */}
      {activeModal === 'events' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-2xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">stream</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Kafka CDC WAL Event Stream</h3>
                  <p className="text-xs text-on-surface-variant">Chronological stream replay on topic safemigrate.wal.orders</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-2 max-h-80 overflow-y-auto font-mono text-xs">
              <div className="p-3 rounded-lg bg-surface-container-low border border-outline-variant/15 flex items-center justify-between">
                <span className="text-primary font-semibold">[BATCH_FLUSH] Backfilled 1,466 rows into orders__shadow</span>
                <span className="text-outline text-[11px]">Just now</span>
              </div>
              <div className="p-3 rounded-lg bg-surface-container-low border border-outline-variant/15 flex items-center justify-between">
                <span className="text-on-surface">[CDC_STREAM_REPLAY] Partition 0 LSN 0/16B2540 replayed 0ms lag</span>
                <span className="text-outline text-[11px]">1m ago</span>
              </div>
              <div className="p-3 rounded-lg bg-surface-container-low border border-outline-variant/15 flex items-center justify-between">
                <span className="text-on-surface">[LOCK_ACQUIRED] Distributed table lease acquired on Redis</span>
                <span className="text-outline text-[11px]">3m ago</span>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-medium text-on-surface transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 5. Approvals Modal */}
      {activeModal === 'approvals' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">verified_user</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Cutover Sign-Off Gate</h3>
                  <p className="text-xs text-on-surface-variant">Dual authorization required before atomic table rename</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="p-4 rounded-xl bg-surface-container-low border border-primary/20 flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="font-mono font-bold text-xs text-on-surface">Target: orders -&gt; orders__shadow</span>
                <span className="text-[10px] font-mono text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">READY FOR CUTOVER</span>
              </div>
              <p className="text-xs text-on-surface-variant font-sans">
                1,466 of 1,466 rows backfilled (100.0%). Replication lag is 0 ms. All pre-flight safety checks PASSED.
              </p>
              <div className="flex items-center gap-2 pt-2 border-t border-outline-variant/10 text-[11px] font-mono text-outline">
                <span className="material-symbols-outlined text-[15px] text-primary">verified</span>
                <span>Sign-off active for current session ({user ? `${user.name} · ${user.role}` : 'Platform Engineer'})</span>
              </div>
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container text-xs font-medium text-on-surface">
                Dismiss
              </button>
              <Link
                href="/overview"
                onClick={() => setActiveModal(null)}
                className="px-4 py-2 rounded-lg bg-primary text-surface-container-lowest text-xs font-bold hover:bg-primary-fixed transition-colors"
              >
                Go to Cutover Console
              </Link>
            </div>
          </div>
        </div>
      )}

      {/* 6. Audit Logs Modal */}
      {activeModal === 'audit' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-2xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">receipt_long</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Infrastructure Audit Trail</h3>
                  <p className="text-xs text-on-surface-variant">Immutable ledger of operator actions and cutovers</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-2 max-h-80 overflow-y-auto font-mono text-xs">
              <div className="p-3 rounded-lg bg-surface-container-low border border-outline-variant/10 flex flex-col gap-1">
                <div className="flex justify-between font-semibold text-on-surface">
                  <span>MIGRATION_INITIALIZED: orders</span>
                  <span className="text-outline text-[11px]">Today 17:45 UTC</span>
                </div>
                <span className="text-[11px] text-on-surface-variant">Operator: @RavirajSonar40 · DDL validated</span>
              </div>
              <div className="p-3 rounded-lg bg-surface-container-low border border-outline-variant/10 flex flex-col gap-1">
                <div className="flex justify-between font-semibold text-on-surface">
                  <span>REDIS_LOCK_ACQUIRED: table:users</span>
                  <span className="text-outline text-[11px]">Today 17:25 UTC</span>
                </div>
                <span className="text-[11px] text-on-surface-variant">Worker: safemigrate_worker_0 · Lease: 30s auto-renew</span>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container hover:bg-surface-container-high text-xs font-medium text-on-surface transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 7. Settings Modal */}
      {activeModal === 'settings' && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-lg rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-6 flex flex-col gap-5">
            <div className="flex items-center justify-between pb-3 border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[24px]">settings</span>
                <div>
                  <h3 className="text-base font-bold text-on-surface font-mono">Engine Migration Parameters</h3>
                  <p className="text-xs text-on-surface-variant">Adjust runtime backfill batching &amp; lock ceilings</p>
                </div>
              </div>
              <button onClick={() => setActiveModal(null)} className="text-outline hover:text-on-surface p-1">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="flex flex-col gap-4 text-xs font-mono">
              <div className="flex flex-col gap-1.5">
                <label className="text-on-surface-variant font-medium">Default Batch Size (rows per chunk)</label>
                <input
                  type="number"
                  value={batchSize}
                  onChange={(e) => setBatchSize(e.target.value)}
                  className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-on-surface-variant font-medium">Adaptive Throttle Delay (milliseconds)</label>
                <input
                  type="number"
                  value={throttleMs}
                  onChange={(e) => setThrottleMs(e.target.value)}
                  className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className="text-on-surface-variant font-medium">Maximum Cutover Lock Timeout (milliseconds)</label>
                <input
                  type="number"
                  value={lockTimeoutMs}
                  onChange={(e) => setLockTimeoutMs(e.target.value)}
                  className="px-3 py-2 rounded-lg bg-surface-container-low border border-outline-variant/30 text-on-surface outline-none focus:border-primary"
                />
              </div>
            </div>

            {settingsSaved && (
              <div className="text-xs text-primary font-mono flex items-center gap-1">
                <span className="material-symbols-outlined text-[16px]">check_circle</span>
                <span>Parameters saved to Redis state coordinator!</span>
              </div>
            )}

            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setActiveModal(null)} className="px-4 py-2 rounded-lg bg-surface-container text-xs font-medium text-on-surface">
                Close
              </button>
              <button onClick={handleSaveSettings} className="px-4 py-2 rounded-lg bg-primary text-surface-container-lowest text-xs font-bold hover:bg-primary-fixed transition-colors">
                Save Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
