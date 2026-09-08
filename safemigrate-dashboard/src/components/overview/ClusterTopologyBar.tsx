export default function ClusterTopologyBar() {
  return (
    <section className="rounded-xl bg-surface-container-low shadow-sm p-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6 border border-outline-variant/10">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-lg bg-surface-container flex items-center justify-center text-primary border border-outline-variant/20">
          <span className="material-symbols-outlined text-[24px]">hub</span>
        </div>
        <div className="flex flex-col">
          <span className="text-base font-medium text-on-surface">Cluster Topology Baseline</span>
          <span className="text-xs text-on-surface-variant">Connected PostgreSQL target fleet across primary zones</span>
        </div>
      </div>

      <div className="flex items-center flex-wrap gap-8">
        <div className="flex flex-col">
          <span className="text-[10px] font-mono uppercase text-on-surface-variant tracking-wider">PG Nodes</span>
          <span className="text-sm font-mono font-semibold text-on-surface">8 Connected</span>
        </div>
        <div className="flex flex-col">
          <span className="text-[10px] font-mono uppercase text-on-surface-variant tracking-wider">Active Workers</span>
          <span className="text-sm font-mono font-semibold text-on-surface">12 Deployed</span>
        </div>
        <div className="flex flex-col">
          <span className="text-[10px] font-mono uppercase text-on-surface-variant tracking-wider">CDC Replication Lag</span>
          <span className="text-sm font-mono font-semibold text-primary">0 ms Baseline</span>
        </div>
        <div className="flex flex-col">
          <span className="text-[10px] font-mono uppercase text-on-surface-variant tracking-wider">Safety Lock Guard</span>
          <span className="text-sm font-mono font-semibold text-on-surface">Active (Enforced)</span>
        </div>
      </div>
    </section>
  );
}
