'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

export default function Sidebar() {
  const pathname = usePathname();

  const navItems = [
    { label: 'Overview', href: '/overview', icon: 'dashboard' },
    { label: 'Migrations', href: '/overview#migrations', icon: 'move_up' },
    { label: 'Databases', href: '/overview#databases', icon: 'database' },
    { label: 'Tables', href: '/overview#tables', icon: 'table_chart' },
    { label: 'Workers', href: '/overview#workers', icon: 'sync_alt' },
    { label: 'Events', href: '/overview#events', icon: 'stream' },
    { label: 'Approvals', href: '/overview#approvals', icon: 'verified_user', badge: '2' },
    { label: 'Audit Logs', href: '/overview#audit', icon: 'receipt_long' },
    { label: 'Settings', href: '/overview#settings', icon: 'settings' },
  ];

  return (
    <aside className="fixed left-0 top-0 h-full w-[220px] bg-surface-container-low border-r border-outline-variant/30 z-50 flex flex-col justify-between select-none">
      <div className="flex flex-col">
        {/* Brand Header */}
        <div className="h-14 px-4 flex items-center gap-2 border-b border-outline-variant/20">
          <div className="w-7 h-7 rounded bg-primary/10 border border-primary/30 flex items-center justify-center">
            <span className="material-symbols-outlined text-primary text-[18px]">cached</span>
          </div>
          <span className="font-semibold text-lg tracking-tight text-on-surface">SafeMigrate</span>
        </div>

        {/* Navigation Items */}
        <nav className="flex flex-col py-2 gap-0.5">
          {navItems.map((item) => {
            const isActive = pathname === item.href || (item.href === '/overview' && pathname === '/');
            return (
              <Link
                key={item.label}
                href={item.href}
                className={`flex items-center justify-between px-4 py-2 text-sm transition-colors ${
                  isActive
                    ? 'bg-surface-container text-primary font-medium border-l-2 border-primary'
                    : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high'
                }`}
              >
                <div className="flex items-center gap-2.5">
                  <span className="material-symbols-outlined text-[18px]">{item.icon}</span>
                  <span>{item.label}</span>
                </div>
                {item.badge && (
                  <span className="text-[11px] font-mono px-1.5 py-0.5 rounded bg-surface-container text-on-surface-variant border border-outline-variant/30">
                    {item.badge}
                  </span>
                )}
              </Link>
            );
          })}
        </nav>
      </div>

      {/* Footer Engine Status */}
      <div className="p-4 border-t border-outline-variant/20">
        <div className="flex items-center justify-between text-on-surface-variant text-xs font-mono">
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-primary inline-block animate-pulse"></span>
            Engine v2.4
          </span>
          <span className="uppercase text-[11px] text-outline font-semibold tracking-wider">Sync OK</span>
        </div>
      </div>
    </aside>
  );
}
