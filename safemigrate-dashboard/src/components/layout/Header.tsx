'use client';

export default function Header() {
  return (
    <header className="fixed top-0 left-[220px] right-0 h-14 bg-surface-container-lowest/90 backdrop-blur-md border-b border-outline-variant/30 z-40 flex items-center justify-between px-6">
      {/* Left: Active Cluster Indicator */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 py-1 px-2.5 rounded bg-surface-container-low border border-outline-variant/30 hover:border-outline-variant/60 cursor-pointer transition-colors text-xs font-mono">
          <span className="material-symbols-outlined text-[16px] text-primary">swap_horiz</span>
          <span className="font-medium text-on-surface">production-db-us-east</span>
          <span className="text-outline">/</span>
          <span className="text-on-surface-variant font-sans">Production (AWS us-east-1)</span>
          <span className="material-symbols-outlined text-[16px] text-on-surface-variant">unfold_more</span>
        </div>
      </div>

      {/* Right: Search, Notifications, Profile */}
      <div className="flex items-center gap-4">
        {/* Global Search Bar */}
        <div className="flex items-center gap-2 bg-surface-container-low border border-outline-variant/30 text-on-surface-variant px-3 py-1.5 rounded text-sm w-72 justify-between">
          <span className="flex items-center gap-2">
            <span className="material-symbols-outlined text-[16px] text-outline">search</span>
            <span className="text-xs text-outline">Search migrations, tables, LSNs...</span>
          </span>
          <kbd className="text-[11px] font-mono bg-surface-container px-1.5 py-0.5 rounded text-outline border border-outline-variant/30">
            ⌘K
          </kbd>
        </div>

        {/* Notifications */}
        <button
          type="button"
          aria-label="Notifications"
          className="p-1.5 text-on-surface-variant hover:text-on-surface transition-colors relative cursor-pointer"
        >
          <span className="material-symbols-outlined text-[20px]">notifications</span>
          <span className="absolute top-1 right-1 w-1.5 h-1.5 bg-primary rounded-full animate-ping"></span>
          <span className="absolute top-1 right-1 w-1.5 h-1.5 bg-primary rounded-full"></span>
        </button>

        <div className="h-4 w-[1px] bg-outline-variant/30"></div>

        {/* User Profile */}
        <div className="flex items-center gap-2 pl-1">
          <div className="relative flex items-center">
            <div className="w-8 h-8 rounded-full bg-surface-container-high border border-outline-variant/40 flex items-center justify-center text-xs font-mono font-semibold text-primary">
              RS
            </div>
            <span className="absolute bottom-0 right-0 w-2 h-2 rounded-full bg-primary ring-2 ring-surface-container-lowest"></span>
          </div>
        </div>
      </div>
    </header>
  );
}
