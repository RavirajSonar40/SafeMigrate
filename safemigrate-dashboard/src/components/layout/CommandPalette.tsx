'use client';

import { useState, useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { isDemoMode, setDemoMode } from '@/lib/demoMode';

interface CommandItem {
  id: string;
  category: string;
  title: string;
  description: string;
  icon: string;
  action: () => void;
  badge?: string;
}

export default function CommandPalette() {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const router = useRouter();

  // Keyboard shortcut listener (Ctrl+K or Cmd+K)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        setIsOpen((prev) => !prev);
      }
      if (e.key === 'Escape' && isOpen) {
        e.preventDefault();
        setIsOpen(false);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) {
      setTimeout(() => inputRef.current?.focus(), 50);
    } else {
      setSearch('');
      setSelectedIndex(0);
    }
  }, [isOpen]);

  const toggleDemo = () => {
    const next = !isDemoMode();
    setDemoMode(next);
    setIsOpen(false);
    window.location.reload();
  };

  const commands: CommandItem[] = [
    {
      id: 'cmd-new-migration',
      category: 'Actions',
      title: 'New Migration',
      description: 'Define DDL and provision a zero-downtime shadow table',
      icon: 'add_circle',
      action: () => {
        setIsOpen(false);
        router.push('/migrations/new');
      }
    },
    {
      id: 'cmd-demo-mode',
      category: 'Actions',
      title: isDemoMode() ? 'Exit Demo Mode' : 'Enter Interactive Demo Mode',
      description: 'Simulate high-throughput 8.2M row workload with live writes',
      icon: isDemoMode() ? 'toggle_on' : 'toggle_off',
      badge: isDemoMode() ? 'ACTIVE' : 'READY',
      action: toggleDemo
    },
    {
      id: 'cmd-operations',
      category: 'Navigation',
      title: 'Operations & Cluster Workers',
      description: 'Deep inspection of WAL Reader, Backfill Worker & Applier',
      icon: 'tune',
      action: () => {
        setIsOpen(false);
        router.push('/operations');
      }
    },
    {
      id: 'cmd-explorer',
      category: 'Navigation',
      title: 'Database Explorer',
      description: 'Inspect tables, columns, indexes, constraints, and dependencies',
      icon: 'database',
      action: () => {
        setIsOpen(false);
        router.push('/explorer');
      }
    },
    {
      id: 'cmd-overview',
      category: 'Navigation',
      title: 'Overview Cockpit',
      description: 'Return to orchestrator dashboard and active migrations',
      icon: 'dashboard',
      action: () => {
        setIsOpen(false);
        router.push('/overview');
      }
    },
    {
      id: 'cmd-audit-whitepaper',
      category: 'Documentation',
      title: '10M Row Performance Audit Whitepaper',
      description: 'Lock duration: 11ms, Throughput: 26,450 rows/sec, Parity: 100%',
      icon: 'description',
      action: () => {
        setIsOpen(false);
        window.open('https://github.com/RavirajSonar40/SafeMigrate/blob/main/docs/benchmarks/10M_MIGRATION_PERFORMANCE_AUDIT.md', '_blank');
      }
    }
  ];

  const filtered = commands.filter((c) =>
    c.title.toLowerCase().includes(search.toLowerCase()) ||
    c.description.toLowerCase().includes(search.toLowerCase()) ||
    c.category.toLowerCase().includes(search.toLowerCase())
  );

  const handleSelect = (item: CommandItem) => {
    item.action();
  };

  const handleArrowKeys = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIndex((prev) => (prev + 1) % Math.max(1, filtered.length));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((prev) => (prev - 1 + filtered.length) % Math.max(1, filtered.length));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (filtered[selectedIndex]) {
        handleSelect(filtered[selectedIndex]);
      }
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-20 sm:pt-28 px-4 bg-black/60 backdrop-blur-sm animate-in fade-in duration-150">
      <div 
        className="w-full max-w-xl bg-surface-container-high border border-outline-variant/30 rounded-2xl shadow-2xl overflow-hidden flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Search bar */}
        <div className="flex items-center px-4 py-3.5 border-b border-outline-variant/20 gap-3">
          <span className="material-symbols-outlined text-primary text-[22px]">search</span>
          <input
            ref={inputRef}
            type="text"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setSelectedIndex(0);
            }}
            onKeyDown={handleArrowKeys}
            placeholder="Search commands, migrations, tables... (or press Esc to close)"
            className="w-full bg-transparent text-on-surface placeholder:text-on-surface-variant/50 text-sm focus:outline-none"
          />
          <kbd className="hidden sm:inline-flex items-center px-2 py-0.5 rounded bg-surface-container text-[11px] font-mono text-on-surface-variant border border-outline-variant/30">
            Esc
          </kbd>
        </div>

        {/* Results List */}
        <div className="max-h-80 overflow-y-auto p-2 space-y-1">
          {filtered.length === 0 ? (
            <div className="py-8 text-center text-xs text-on-surface-variant">
              No matching commands found
            </div>
          ) : (
            filtered.map((item, index) => {
              const isSelected = index === selectedIndex;
              return (
                <button
                  key={item.id}
                  onClick={() => handleSelect(item)}
                  onMouseEnter={() => setSelectedIndex(index)}
                  className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-left transition-all ${
                    isSelected
                      ? 'bg-primary/15 text-on-surface border border-primary/30'
                      : 'text-on-surface-variant hover:bg-surface-container'
                  }`}
                >
                  <div className="flex items-center gap-3">
                    <span className={`material-symbols-outlined text-[20px] ${isSelected ? 'text-primary' : 'text-on-surface-variant'}`}>
                      {item.icon}
                    </span>
                    <div>
                      <div className="text-xs font-semibold text-on-surface flex items-center gap-2">
                        {item.title}
                        {item.badge && (
                          <span className="px-1.5 py-0.2 rounded text-[10px] font-mono bg-primary/20 text-primary border border-primary/30">
                            {item.badge}
                          </span>
                        )}
                      </div>
                      <div className="text-[11px] text-on-surface-variant line-clamp-1">
                        {item.description}
                      </div>
                    </div>
                  </div>
                  <span className="text-[10px] font-mono text-on-surface-variant/60 uppercase">
                    {item.category}
                  </span>
                </button>
              );
            })
          )}
        </div>

        {/* Footer shortcuts */}
        <div className="px-4 py-2 bg-surface-container border-t border-outline-variant/10 flex items-center justify-between text-[11px] text-on-surface-variant font-mono">
          <div className="flex items-center gap-3">
            <span>↑↓ Navigate</span>
            <span>↵ Select</span>
            <span>Esc Close</span>
          </div>
          <span className="text-primary font-semibold">SafeMigrate v1.0</span>
        </div>
      </div>
    </div>
  );
}
