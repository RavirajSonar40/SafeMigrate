/* eslint-disable @next/next/no-img-element */
'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useAuth } from '@/context/AuthContext';

export default function Header() {
  const { user, logout } = useAuth();
  const [showClusterMenu, setShowClusterMenu] = useState(false);
  const [showSearchModal, setShowSearchModal] = useState(false);
  const [showNotifications, setShowNotifications] = useState(false);
  const [showProfileMenu, setShowProfileMenu] = useState(false);
  const [activeCluster, setActiveCluster] = useState('production-db-us-east');
  const [searchQuery, setSearchQuery] = useState('');

  // ⌘K hotkey listener
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setShowSearchModal((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const clusters = [
    { id: 'production-db-us-east', name: 'production-db-us-east', region: 'AWS us-east-1', status: 'ONLINE', nodes: 8 },
    { id: 'staging-db-eu-west', name: 'staging-db-eu-west', region: 'AWS eu-west-1', status: 'STANDBY', nodes: 4 },
    { id: 'analytics-replica', name: 'analytics-replica-us', region: 'GCP us-central1', status: 'READ-ONLY', nodes: 2 },
  ];

  const notifications = [
    { id: 1, title: 'Replication In-Sync', desc: 'Table orders CDC lag reached 0 ms baseline.', time: '2m ago', type: 'success' },
    { id: 2, title: 'Preflight Check Passed', desc: 'Zero lock contention detected on target table.', time: '14m ago', type: 'info' },
    { id: 3, title: 'Cutover Approval Ready', desc: 'Dual sign-off requested for active migration.', time: '28m ago', type: 'warning' },
  ];

  const searchableItems = [
    { type: 'Table', title: 'public.orders', subtitle: '1,466 rows · Primary Key (id)', link: '/overview' },
    { type: 'Migration', title: 'mig_orders_add_priority', subtitle: 'Status: READY_FOR_CUTOVER', link: '/overview' },
    { type: 'Pod', title: 'safemigrate_worker_0', subtitle: 'Status: RUNNING · 9,420 r/s', link: '/overview#workers' },
    { type: 'Pod', title: 'safemigrate_postgres', subtitle: 'Port 5432 · wal_level=logical', link: '/overview#workers' },
  ];

  const filteredItems = searchableItems.filter(
    (item) => item.title.toLowerCase().includes(searchQuery.toLowerCase()) || item.type.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <>
      <header className="fixed top-0 left-[220px] right-0 h-14 bg-surface-container-lowest/90 backdrop-blur-md border-b border-outline-variant/30 z-40 flex items-center justify-between px-6">
        {/* Left: Active Cluster Dropdown */}
        <div className="relative">
          <button
            onClick={() => setShowClusterMenu(!showClusterMenu)}
            className="flex items-center gap-2 py-1 px-2.5 rounded-lg bg-surface-container-low border border-outline-variant/30 hover:border-outline-variant/60 cursor-pointer transition-colors text-xs font-mono"
          >
            <span className="material-symbols-outlined text-[16px] text-primary">swap_horiz</span>
            <span className="font-semibold text-on-surface">{activeCluster}</span>
            <span className="text-outline">/</span>
            <span className="text-on-surface-variant font-sans hidden sm:inline">
              {clusters.find((c) => c.id === activeCluster)?.region}
            </span>
            <span className="material-symbols-outlined text-[16px] text-on-surface-variant">unfold_more</span>
          </button>

          {/* Cluster Selector Dropdown */}
          {showClusterMenu && (
            <div className="absolute top-12 left-0 w-80 rounded-xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-2 z-50 animate-fade-in flex flex-col gap-1">
              <div className="px-3 py-1.5 text-[10px] font-mono text-outline uppercase tracking-wider">
                Select Connected Target Cluster
              </div>
              {clusters.map((cluster) => (
                <button
                  key={cluster.id}
                  onClick={() => {
                    setActiveCluster(cluster.id);
                    setShowClusterMenu(false);
                  }}
                  className={`flex items-center justify-between p-2.5 rounded-lg text-left transition-colors text-xs ${
                    activeCluster === cluster.id
                      ? 'bg-surface-container text-primary font-medium border border-primary/20'
                      : 'hover:bg-surface-container-high text-on-surface'
                  }`}
                >
                  <div className="flex flex-col">
                    <span className="font-mono font-semibold">{cluster.name}</span>
                    <span className="text-[11px] text-on-surface-variant">{cluster.region} · {cluster.nodes} Nodes</span>
                  </div>
                  <span className={`text-[10px] font-mono px-2 py-0.5 rounded-full ${
                    cluster.status === 'ONLINE' ? 'bg-primary/10 text-primary border border-primary/30' : 'bg-surface-container text-outline'
                  }`}>
                    {cluster.status}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Right: Search, Notifications, Profile */}
        <div className="flex items-center gap-4">
          {/* Global Search Bar (opens search dialog) */}
          <button
            onClick={() => setShowSearchModal(true)}
            className="flex items-center gap-2 bg-surface-container-low hover:bg-surface-container border border-outline-variant/30 text-on-surface-variant px-3 py-1.5 rounded-lg text-sm w-56 sm:w-72 justify-between cursor-pointer transition-colors"
          >
            <span className="flex items-center gap-2 truncate">
              <span className="material-symbols-outlined text-[16px] text-outline">search</span>
              <span className="text-xs text-outline truncate">Search migrations, tables...</span>
            </span>
            <kbd className="text-[11px] font-mono bg-surface-container px-1.5 py-0.5 rounded text-outline border border-outline-variant/30 shrink-0">
              ⌘K
            </kbd>
          </button>

          {/* Notifications Bell */}
          <div className="relative">
            <button
              onClick={() => setShowNotifications(!showNotifications)}
              aria-label="Notifications"
              className="p-1.5 text-on-surface-variant hover:text-on-surface transition-colors relative cursor-pointer rounded-lg hover:bg-surface-container"
            >
              <span className="material-symbols-outlined text-[20px]">notifications</span>
              <span className="absolute top-1 right-1 w-2 h-2 bg-primary rounded-full animate-ping"></span>
              <span className="absolute top-1 right-1 w-2 h-2 bg-primary rounded-full"></span>
            </button>

            {/* Notifications Dropdown */}
            {showNotifications && (
              <div className="absolute right-0 top-12 w-80 rounded-xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-3 z-50 animate-fade-in flex flex-col gap-2">
                <div className="flex items-center justify-between pb-2 border-b border-outline-variant/15 text-xs font-semibold text-on-surface">
                  <span>Cluster Notifications</span>
                  <span className="text-[10px] font-mono text-primary bg-primary/10 px-1.5 py-0.5 rounded border border-primary/20">
                    Live Feed
                  </span>
                </div>
                <div className="flex flex-col gap-1.5">
                  {notifications.map((n) => (
                    <div key={n.id} className="p-2 rounded-lg bg-surface-container-low hover:bg-surface-container text-xs flex flex-col gap-0.5 border border-outline-variant/10">
                      <div className="flex items-center justify-between font-semibold text-on-surface text-[11px]">
                        <span>{n.title}</span>
                        <span className="text-[10px] text-outline font-mono">{n.time}</span>
                      </div>
                      <span className="text-[11px] text-on-surface-variant leading-tight">{n.desc}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          <div className="h-4 w-[1px] bg-outline-variant/30"></div>

          {/* User Profile / OAuth Session */}
          <div className="relative">
            <button
              onClick={() => setShowProfileMenu(!showProfileMenu)}
              className="flex items-center gap-2.5 p-1 rounded-lg hover:bg-surface-container transition-colors cursor-pointer"
            >
              <div className="relative flex items-center">
                <img
                  src={user?.avatar || 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80'}
                  alt={user?.name || 'User Avatar'}
                  className="w-8 h-8 rounded-full object-cover border border-primary/40 ring-1 ring-primary/20"
                />
                <span className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-primary ring-2 ring-surface-container-lowest"></span>
              </div>
              <div className="hidden md:flex flex-col text-left leading-none">
                <span className="text-xs font-semibold text-on-surface">{user?.name || 'Sara Chen'}</span>
                <span className="text-[10px] font-mono text-primary mt-0.5">{user?.role || 'Staff SRE'}</span>
              </div>
              <span className="material-symbols-outlined text-[16px] text-outline">expand_more</span>
            </button>

            {/* Profile Dropdown */}
            {showProfileMenu && (
              <div className="absolute right-0 top-12 w-64 rounded-xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl p-3 z-50 animate-fade-in flex flex-col gap-3">
                <div className="flex items-center gap-3 pb-2.5 border-b border-outline-variant/15">
                  <img
                    src={user?.avatar || 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80'}
                    alt="User"
                    className="w-10 h-10 rounded-full object-cover border border-primary/40"
                  />
                  <div className="flex flex-col truncate">
                    <span className="text-xs font-bold text-on-surface">{user?.name || 'Sara Chen'}</span>
                    <span className="text-[10px] text-on-surface-variant truncate">{user?.email || 'sara.chen@enterprise.internal'}</span>
                    <span className="text-[10px] font-mono text-primary uppercase mt-0.5">
                      OAuth 2.0: {user?.provider || 'GitHub'}
                    </span>
                  </div>
                </div>

                <div className="flex flex-col gap-1 text-xs">
                  <div className="px-2 py-1.5 rounded-lg text-on-surface-variant flex items-center justify-between text-[11px] font-mono">
                    <span>Cluster Perms</span>
                    <span className="text-primary font-semibold">Admin (Cutover)</span>
                  </div>
                  <div className="px-2 py-1.5 rounded-lg text-on-surface-variant flex items-center justify-between text-[11px] font-mono">
                    <span>Team</span>
                    <span className="text-on-surface">{user?.team || 'Database Reliability'}</span>
                  </div>
                </div>

                <div className="pt-2 border-t border-outline-variant/15 flex flex-col gap-1">
                  <Link
                    href="/login"
                    onClick={() => setShowProfileMenu(false)}
                    className="w-full py-1.5 px-3 rounded-lg hover:bg-surface-container text-xs text-on-surface flex items-center gap-2 transition-colors"
                  >
                    <span className="material-symbols-outlined text-[16px] text-tertiary">switch_account</span>
                    <span>Switch Account</span>
                  </Link>

                  <button
                    onClick={() => {
                      setShowProfileMenu(false);
                      logout();
                    }}
                    className="w-full py-1.5 px-3 rounded-lg hover:bg-error-container/20 text-xs text-error flex items-center gap-2 transition-colors text-left cursor-pointer"
                  >
                    <span className="material-symbols-outlined text-[16px]">logout</span>
                    <span>Sign Out</span>
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Global Spotlight Search Modal (⌘K) */}
      {showSearchModal && (
        <div className="fixed inset-0 z-50 flex items-start justify-center pt-24 p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl overflow-hidden flex flex-col">
            {/* Search Input Bar */}
            <div className="flex items-center gap-3 px-4 py-3 bg-surface-container-low border-b border-outline-variant/20">
              <span className="material-symbols-outlined text-primary text-[22px]">search</span>
              <input
                type="text"
                autoFocus
                placeholder="Search tables, migrations, Kafka topics, pod fleet..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full bg-transparent text-sm text-on-surface placeholder:text-outline outline-none"
              />
              <button
                onClick={() => setShowSearchModal(false)}
                className="text-outline hover:text-on-surface p-1 rounded"
              >
                <span className="material-symbols-outlined text-[18px]">close</span>
              </button>
            </div>

            {/* Results */}
            <div className="p-3 max-h-80 overflow-y-auto flex flex-col gap-1.5">
              <div className="text-[10px] font-mono text-outline px-2 uppercase">Matching Platform Resources</div>
              {filteredItems.map((item, idx) => (
                <Link
                  key={idx}
                  href={item.link}
                  onClick={() => setShowSearchModal(false)}
                  className="flex items-center justify-between p-2.5 rounded-xl hover:bg-surface-container transition-colors text-xs"
                >
                  <div className="flex items-center gap-3">
                    <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-surface-container-high text-primary border border-outline-variant/20">
                      {item.type}
                    </span>
                    <div className="flex flex-col">
                      <span className="font-semibold text-on-surface font-mono">{item.title}</span>
                      <span className="text-[11px] text-on-surface-variant">{item.subtitle}</span>
                    </div>
                  </div>
                  <span className="material-symbols-outlined text-[16px] text-outline">arrow_forward</span>
                </Link>
              ))}
            </div>

            {/* Footer */}
            <div className="px-4 py-2 bg-surface-container-low border-t border-outline-variant/15 flex items-center justify-between text-[11px] font-mono text-outline">
              <span>Navigate with arrow keys</span>
              <span>ESC to close</span>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
