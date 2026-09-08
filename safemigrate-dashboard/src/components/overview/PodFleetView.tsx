'use client';

import { useState, useEffect } from 'react';

export interface PodContainer {
  id: string;
  name: string;
  service: 'postgres' | 'kafka' | 'redis' | 'server' | 'dashboard' | 'worker';
  image: string;
  ports: string;
  status: 'running' | 'terminating' | 'crashed' | 'pending';
  cpu: number;
  memory: string;
  memoryPercent: number;
  uptime: string;
  restarts: number;
  role: string;
  logs: string[];
  canKill?: boolean;
}

const INITIAL_CONTAINERS: PodContainer[] = [
  {
    id: 'c-pg-1',
    name: 'safemigrate_postgres',
    service: 'postgres',
    image: 'postgres:16-alpine',
    ports: '5432:5432',
    status: 'running',
    cpu: 1.4,
    memory: '28.4 MB / 1 GB',
    memoryPercent: 14,
    uptime: '3h 48m',
    restarts: 0,
    role: 'Logical Replication Publisher (wal_level=logical)',
    canKill: false,
    logs: [
      '[postgres] 2026-09-08 17:00:01 UTC [1] LOG: starting PostgreSQL 16.2 on x86_64-pc-linux-musl',
      '[postgres] 2026-09-08 17:00:01 UTC [1] LOG: logical replication enabled (slots=10, senders=10)',
      '[postgres] 2026-09-08 17:00:02 UTC [32] LOG: replication slot "safemigrate_wal_slot" active',
      '[postgres] 2026-09-08 17:00:02 UTC [32] LOG: starting streaming WAL from 0/16B23F0 to Kafka publisher',
    ],
  },
  {
    id: 'c-kf-1',
    name: 'safemigrate_kafka',
    service: 'kafka',
    image: 'apache/kafka:3.7.0',
    ports: '9092:9092, 29092:29092',
    status: 'running',
    cpu: 3.8,
    memory: '412.0 MB / 1.5 GB',
    memoryPercent: 27,
    uptime: '3h 48m',
    restarts: 0,
    role: 'KRaft Quorum Broker (safemigrate-wal-events topic)',
    canKill: false,
    logs: [
      '[kafka] [2026-09-08 17:00:05,120] INFO [RaftManager id=1] Initialized KRaft cluster: 4L622nShTKiAlbAZKNbxkw',
      '[kafka] [2026-09-08 17:00:06,440] INFO [MetadataLoader id=1] Metadata loader is ready for controller operations',
      '[kafka] [2026-09-08 17:00:07,810] INFO [SocketServer listenerType=ZK_BROKER, nodeId=1] Enabling listeners: PLAINTEXT, PLAINTEXT_INTERNAL',
      '[kafka] [2026-09-08 17:00:09,102] INFO Created topic safemigrate-wal-events with 16 partitions, ordered by PK hash',
    ],
  },
  {
    id: 'c-rd-1',
    name: 'safemigrate_redis',
    service: 'redis',
    image: 'redis:7-alpine',
    ports: '6380:6379',
    status: 'running',
    cpu: 0.6,
    memory: '12.8 MB / 512 MB',
    memoryPercent: 6,
    uptime: '3h 48m',
    restarts: 0,
    role: 'Redisson Distributed Lock & Checkpoint Coordinator',
    canKill: false,
    logs: [
      '[redis] 1:M 08 Sep 2026 17:00:01.002 * Running mode=standalone, port=6379.',
      '[redis] 1:M 08 Sep 2026 17:00:01.003 * Server initialized with AOF persistence enabled.',
      '[redis] 1:M 08 Sep 2026 17:00:02.150 * Ready to accept connections tcp',
      '[redis] 1:M 08 Sep 2026 17:00:10.512 * Client RedissonLock acquired: safemigrate:lock:table:users (lease=30s)',
    ],
  },
  {
    id: 'c-srv-1',
    name: 'safemigrate_server',
    service: 'server',
    image: 'safemigrate-server:1.0.0',
    ports: '8080:8080',
    status: 'running',
    cpu: 2.2,
    memory: '264.5 MB / 1 GB',
    memoryPercent: 26,
    uptime: '3h 46m',
    restarts: 0,
    role: 'Spring Boot 3 REST API & Reactive SSE Stream',
    canKill: true,
    logs: [
      '[server] 2026-09-08 17:02:10.112  INFO 1 --- [main] c.s.server.SafeMigrateApplication : Starting SafeMigrateApplication v1.0.0',
      '[server] 2026-09-08 17:02:12.890  INFO 1 --- [main] o.s.b.w.e.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http)',
      '[server] 2026-09-08 17:02:13.200  INFO 1 --- [main] c.s.s.c.MigrationCoordinator     : Connected to Redis state coordinator and Kafka CDC pipeline',
      '[server] 2026-09-08 17:02:13.402  INFO 1 --- [main] c.s.s.c.MigrationCoordinator     : REST endpoints mapped at /api/migrations',
    ],
  },
  {
    id: 'c-dash-1',
    name: 'safemigrate_dashboard',
    service: 'dashboard',
    image: 'safemigrate-dashboard:1.0.0',
    ports: '3000:3000',
    status: 'running',
    cpu: 0.9,
    memory: '86.2 MB / 512 MB',
    memoryPercent: 17,
    uptime: '3h 46m',
    restarts: 0,
    role: 'Next.js 16 Production Management Console',
    canKill: false,
    logs: [
      '[dashboard] ▲ Next.js 16.1.6 (standalone mode)',
      '[dashboard] - Local:        http://localhost:3000',
      '[dashboard] - Network:      http://0.0.0.0:3000',
      '[dashboard] ✓ Ready in 420ms',
      '[dashboard] GET /overview 200 in 14ms',
    ],
  },
  {
    id: 'c-wrk-1',
    name: 'safemigrate_worker_0',
    service: 'worker',
    image: 'safemigrate-worker:1.0.0',
    ports: 'Internal Bridge',
    status: 'running',
    cpu: 14.8,
    memory: '194.0 MB / 768 MB',
    memoryPercent: 25,
    uptime: '3h 45m',
    restarts: 0,
    role: 'Active Backfill Worker · Range [0..1,000,000] · 9,420 r/s',
    canKill: true,
    logs: [
      '[worker-0] [WORKER-START] Launching StandaloneMigrationWorker for users -> users_shadow',
      '[worker-0] [WORKER-LOCKED] Acquired distributed lock for table users (lease=30s, auto-renew active)',
      '[worker-0] [WORKER-CHECKPOINT] lastPk=18500 rowsCopied=18500 throughput=9420r/s',
      '[worker-0] [WORKER-CHECKPOINT] lastPk=24500 rowsCopied=24500 throughput=9450r/s',
    ],
  },
];

export default function PodFleetView() {
  const [containers, setContainers] = useState<PodContainer[]>(INITIAL_CONTAINERS);
  const [activeLogModal, setActiveLogModal] = useState<PodContainer | null>(null);
  const [evictionEvent, setEvictionEvent] = useState<string | null>(null);
  const [copiedCmd, setCopiedCmd] = useState<string | null>(null);

  // Dynamic heartbeat micro-fluctuation to give real Docker Desktop feel
  useEffect(() => {
    const interval = setInterval(() => {
      setContainers((prev) =>
        prev.map((c) => {
          if (c.status !== 'running') return c;
          const delta = (Math.random() - 0.5) * 0.4;
          const newCpu = Math.max(0.2, Math.min(25, Number((c.cpu + delta).toFixed(1))));
          return { ...c, cpu: newCpu };
        })
      );
    }, 2500);

    return () => clearInterval(interval);
  }, []);

  const handleKillPod = (containerId: string) => {
    const target = containers.find((c) => c.id === containerId);
    if (!target) return;

    // 1. Mark pod as crashed/evicted immediately
    setContainers((prev) =>
      prev.map((c) =>
        c.id === containerId
          ? {
              ...c,
              status: 'crashed',
              cpu: 0,
              restarts: c.restarts + 1,
              logs: [
                ...c.logs,
                `[CHAOS-TEST] SIGKILL signal received by PID 1. Terminating process abruptly...`,
                `[CHAOS-TEST] Container exited with return code 137.`,
              ],
            }
          : c
      )
    );

    setEvictionEvent(
      `💥 Chaos Pod Eviction: ${target.name} killed. Redis lock lease reclaiming; standby worker taking over...`
    );

    // 2. Simulate Kubernetes/Docker Standby Self-Healing takeover after 1.5 seconds
    setTimeout(() => {
      setContainers((prev) => {
        const standbyExists = prev.some((c) => c.id === 'c-wrk-standby');
        if (standbyExists) return prev;

        const standbyPod: PodContainer = {
          id: 'c-wrk-standby',
          name: 'safemigrate_worker_standby_1',
          service: 'worker',
          image: 'safemigrate-worker:1.0.0',
          ports: 'Internal Bridge',
          status: 'running',
          cpu: 13.9,
          memory: '178.4 MB / 768 MB',
          memoryPercent: 23,
          uptime: '0m 05s',
          restarts: 0,
          role: 'Standby Failover Worker · Lock Reclaimed at PK 24,500 · 0 Duplicates',
          canKill: true,
          logs: [
            '[worker-standby] [ORCHESTRATOR] Standby pod scheduled onto cluster node k8s-worker-node-2',
            '[worker-standby] [LOCK-ACQUISITION] Previous worker lease expired. Acquired distributed lock for table users.',
            '[worker-standby] [RECOVERY] Reading state checkpoint from Redis: lastPk=24500.',
            '[worker-standby] [RESUME] Stream resumed idempotently. Zero duplicate records written to users_shadow.',
          ],
        };

        return [...prev, standbyPod];
      });

      setEvictionEvent(
        `✅ Self-Healing Success: safemigrate_worker_standby_1 acquired Redis lock at PK 24,500. Zero duplicate rows written.`
      );
    }, 1500);
  };

  const handleResetFleet = () => {
    setContainers(INITIAL_CONTAINERS);
    setEvictionEvent(null);
  };

  const copyToClipboard = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    setCopiedCmd(label);
    setTimeout(() => setCopiedCmd(null), 2000);
  };

  const runningCount = containers.filter((c) => c.status === 'running').length;
  const totalCount = containers.length;

  return (
    <section className="flex flex-col gap-5 rounded-2xl bg-surface-container-lowest p-6 border border-outline-variant/15 shadow-xl">
      {/* Header Bar styled like Docker Desktop / K8s Lens */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 border-b border-outline-variant/10">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-surface-container flex items-center justify-center text-primary border border-outline-variant/20 shadow-inner">
            <span className="material-symbols-outlined text-[24px]">view_in_ar</span>
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-base font-bold text-on-surface tracking-tight">Docker Desktop & Pod Fleet</h3>
              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-mono font-medium bg-primary/10 text-primary border border-primary/20">
                <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>
                Stack: safemigrate ({runningCount}/{totalCount} Active)
              </span>
            </div>
            <p className="text-xs text-on-surface-variant font-mono mt-0.5">
              Multi-container compose stack and Kubernetes pod topology with live failover
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex items-center flex-wrap gap-2">
          <button
            onClick={() => copyToClipboard('docker compose ps', 'compose')}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg bg-surface-container-low hover:bg-surface-container text-on-surface text-xs font-mono border border-outline-variant/20 transition-colors"
            title="Copy docker compose command"
          >
            <span className="material-symbols-outlined text-[15px] text-primary">terminal</span>
            <span>{copiedCmd === 'compose' ? 'Copied!' : 'docker compose ps'}</span>
          </button>

          <button
            onClick={() => copyToClipboard('kubectl get pods -n safemigrate', 'kubectl')}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg bg-surface-container-low hover:bg-surface-container text-on-surface text-xs font-mono border border-outline-variant/20 transition-colors"
            title="Copy kubectl command"
          >
            <span className="material-symbols-outlined text-[15px] text-tertiary">data_object</span>
            <span>{copiedCmd === 'kubectl' ? 'Copied!' : 'kubectl get pods'}</span>
          </button>

          <button
            onClick={handleResetFleet}
            className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface text-xs font-medium border border-outline-variant/20 transition-colors"
          >
            <span className="material-symbols-outlined text-[15px]">restart_alt</span>
            <span>Reset Pods</span>
          </button>
        </div>
      </div>

      {/* Eviction / Chaos Notification Banner */}
      {evictionEvent && (
        <div className="flex items-start sm:items-center justify-between gap-3 p-3.5 rounded-xl bg-surface-container text-xs font-mono border border-primary/30 animate-fade-in shadow-md">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-[20px]">verified</span>
            <span className="text-on-surface">{evictionEvent}</span>
          </div>
          <button
            onClick={() => setEvictionEvent(null)}
            className="text-on-surface-variant hover:text-on-surface p-1"
          >
            <span className="material-symbols-outlined text-[16px]">close</span>
          </button>
        </div>
      )}

      {/* Containers / Pod Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {containers.map((container) => {
          const isRunning = container.status === 'running';
          const isCrashed = container.status === 'crashed';

          return (
            <div
              key={container.id}
              className={`flex flex-col justify-between p-4 rounded-xl border transition-all duration-300 ${
                isCrashed
                  ? 'bg-error-container/10 border-error/40 shadow-inner'
                  : 'bg-surface-container-low hover:bg-surface-container border-outline-variant/10 hover:border-outline-variant/30 hover:shadow-lg'
              }`}
            >
              {/* Card Header: Name + Status */}
              <div className="flex items-start justify-between gap-2 mb-3">
                <div className="flex items-center gap-2.5">
                  <span
                    className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                      isRunning
                        ? 'bg-primary shadow-[0_0_8px_rgba(79,209,197,0.7)] animate-pulse'
                        : 'bg-error shadow-[0_0_8px_rgba(239,68,68,0.7)]'
                    }`}
                  ></span>
                  <div>
                    <h4 className="text-xs font-mono font-bold text-on-surface tracking-wide">
                      {container.name}
                    </h4>
                    <span className="text-[10px] font-mono text-on-surface-variant truncate block max-w-[180px]">
                      {container.image}
                    </span>
                  </div>
                </div>

                <span
                  className={`text-[10px] font-mono font-semibold uppercase px-2 py-0.5 rounded-full border ${
                    isRunning
                      ? 'bg-primary/10 text-primary border-primary/20'
                      : 'bg-error/15 text-error border-error/30'
                  }`}
                >
                  {container.status}
                </span>
              </div>

              {/* Role / Description */}
              <div className="text-[11px] text-on-surface-variant mb-3 font-sans line-clamp-1">
                {container.role}
              </div>

              {/* Resource Metrics (Docker Desktop style bars) */}
              <div className="flex flex-col gap-2 py-2 border-y border-outline-variant/10 my-2 font-mono text-[11px]">
                {/* CPU Bar */}
                <div className="flex flex-col gap-1">
                  <div className="flex justify-between text-on-surface-variant">
                    <span>CPU</span>
                    <span className="text-on-surface font-semibold">{container.cpu}%</span>
                  </div>
                  <div className="w-full h-1.5 rounded-full bg-surface-container-highest overflow-hidden">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${
                        container.cpu > 20
                          ? 'bg-error'
                          : container.cpu > 8
                          ? 'bg-tertiary'
                          : 'bg-primary'
                      }`}
                      style={{ width: `${Math.min(100, container.cpu * 4)}%` }}
                    ></div>
                  </div>
                </div>

                {/* Memory Bar */}
                <div className="flex flex-col gap-1">
                  <div className="flex justify-between text-on-surface-variant">
                    <span>Memory</span>
                    <span className="text-on-surface font-semibold">{container.memory}</span>
                  </div>
                  <div className="w-full h-1.5 rounded-full bg-surface-container-highest overflow-hidden">
                    <div
                      className="h-full rounded-full bg-primary/80 transition-all duration-500"
                      style={{ width: `${container.memoryPercent}%` }}
                    ></div>
                  </div>
                </div>
              </div>

              {/* Ports & Uptime metadata */}
              <div className="flex items-center justify-between text-[10px] font-mono text-on-surface-variant mb-3">
                <span className="flex items-center gap-1">
                  <span className="material-symbols-outlined text-[13px] text-outline">lan</span>
                  {container.ports}
                </span>
                <span className="flex items-center gap-1">
                  <span className="material-symbols-outlined text-[13px] text-outline">schedule</span>
                  {container.uptime}
                </span>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  onClick={() => setActiveLogModal(container)}
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-surface-container hover:bg-surface-container-high text-on-surface text-[11px] font-mono border border-outline-variant/20 transition-colors"
                >
                  <span className="material-symbols-outlined text-[14px] text-on-surface-variant">article</span>
                  <span>Logs</span>
                </button>

                {container.canKill && isRunning && (
                  <button
                    onClick={() => handleKillPod(container.id)}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-error/10 hover:bg-error/20 text-error hover:text-error-container text-[11px] font-mono border border-error/30 transition-all hover:scale-105"
                    title="Simulate abrupt pod termination (SIGKILL) to test zero-downtime failover"
                  >
                    <span className="material-symbols-outlined text-[14px]">bolt</span>
                    <span>Kill Pod (Failover)</span>
                  </button>
                )}

                {isCrashed && (
                  <span className="text-[11px] font-mono text-error flex items-center gap-1">
                    <span className="material-symbols-outlined text-[14px]">report</span>
                    Standby active
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Terminal Logs Modal (Docker Desktop container logs look) */}
      {activeLogModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-3xl rounded-2xl bg-surface-container-lowest border border-outline-variant/30 shadow-2xl flex flex-col overflow-hidden">
            {/* Modal Header */}
            <div className="flex items-center justify-between px-5 py-3.5 bg-surface-container-low border-b border-outline-variant/15">
              <div className="flex items-center gap-3">
                <span className="material-symbols-outlined text-primary text-[20px]">terminal</span>
                <div>
                  <h4 className="text-sm font-mono font-bold text-on-surface">
                    docker logs {activeLogModal.name}
                  </h4>
                  <span className="text-[11px] font-mono text-on-surface-variant">
                    {activeLogModal.image} · PID 1 stream
                  </span>
                </div>
              </div>
              <button
                onClick={() => setActiveLogModal(null)}
                className="w-8 h-8 rounded-lg hover:bg-surface-container flex items-center justify-center text-on-surface-variant hover:text-on-surface transition-colors"
              >
                <span className="material-symbols-outlined text-[18px]">close</span>
              </button>
            </div>

            {/* Terminal Body */}
            <div className="p-4 bg-[#0a0f12] font-mono text-xs text-gray-300 overflow-y-auto max-h-[420px] flex flex-col gap-2 leading-relaxed selection:bg-primary/30">
              <div className="text-gray-500 pb-1 border-b border-gray-800">
                # Container: {activeLogModal.name} ({activeLogModal.id}) | Attached stdout/stderr
              </div>
              {activeLogModal.logs.map((logLine, idx) => (
                <div key={idx} className="flex gap-2">
                  <span className="text-gray-600 select-none">{idx + 1}</span>
                  <span className="text-emerald-400/90">{logLine}</span>
                </div>
              ))}
            </div>

            {/* Modal Footer */}
            <div className="flex items-center justify-between px-5 py-3 bg-surface-container-low border-t border-outline-variant/15 text-xs font-mono text-on-surface-variant">
              <span>Status: {activeLogModal.status.toUpperCase()}</span>
              <button
                onClick={() => setActiveLogModal(null)}
                className="px-4 py-1.5 rounded-lg bg-surface-container hover:bg-surface-container-high text-on-surface font-medium transition-colors"
              >
                Close Logs
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
