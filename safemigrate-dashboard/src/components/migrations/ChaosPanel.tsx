'use client';

import { useState } from 'react';
import { ChaosAction, ChaosInjectionResponse, RecoveryResponse } from '@/lib/types';
import { injectChaos, recoverMigration } from '@/lib/api';
import { isDemoMode, injectDemoChaos, recoverDemoMigration } from '@/lib/demoMode';

interface ChaosPanelProps {
  migrationId: string;
  state?: string;
  currentLsn?: string;
  activeChaosAction?: string | null;
  lastCheckpointPk?: number;
  resilienceEvents?: string[];
  onActionTriggered?: () => void;
  onChaosInjected?: (resp: ChaosInjectionResponse) => void;
  onRecovered?: (resp: RecoveryResponse) => void;
}

export default function ChaosPanel({
  migrationId,
  state = 'CATCHING_UP',
  currentLsn = '0/5A81F21',
  activeChaosAction,
  lastCheckpointPk,
  resilienceEvents = [],
  onActionTriggered,
  onChaosInjected,
  onRecovered,
}: ChaosPanelProps) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const isPausedOrInterrupted = state === 'PAUSED' || activeChaosAction != null;

  const handleInject = async (action: ChaosAction) => {
    setIsSubmitting(true);
    setFeedback(null);
    try {
      let res: ChaosInjectionResponse;
      if (isDemoMode() || migrationId.startsWith('demo_')) {
        res = injectDemoChaos(action);
      } else {
        res = await injectChaos(migrationId, action);
      }
      setFeedback({ type: 'success', message: res.message });
      onChaosInjected?.(res);
      onActionTriggered?.();
    } catch (err: any) {
      setFeedback({ type: 'error', message: err.message || 'Failed to inject failure' });
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRecover = async () => {
    setIsSubmitting(true);
    setFeedback(null);
    try {
      let res: RecoveryResponse;
      if (isDemoMode() || migrationId.startsWith('demo_')) {
        res = recoverDemoMigration();
      } else {
        res = await recoverMigration(migrationId);
      }
      setFeedback({ type: 'success', message: res.message });
      onRecovered?.(res);
      onActionTriggered?.();
    } catch (err: any) {
      setFeedback({ type: 'error', message: err.message || 'Failed to recover worker' });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="bg-surface-container border border-outline-variant/30 rounded-2xl p-6 flex flex-col gap-6 shadow-sm">
      {/* Title */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-outline-variant/15">
        <div>
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-amber-400 text-[22px]">warning</span>
            <h3 className="text-base font-bold text-on-surface">Failure Injection & Resilience Cockpit</h3>
          </div>
          <p className="text-xs text-on-surface-variant mt-0.5">
            Safely test cluster fault tolerance, checkpoint recovery, and zero-loss guarantees under real failure scenarios
          </p>
        </div>

        <div className="flex items-center gap-2">
          {activeChaosAction ? (
            <span className="px-3 py-1 rounded-full text-xs font-mono font-bold bg-amber-500/15 text-amber-300 border border-amber-500/30 animate-pulse flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-amber-400"></span>
              <span>CHAOS ACTIVE: {activeChaosAction}</span>
            </span>
          ) : (
            <span className="px-3 py-1 rounded-full text-xs font-mono font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
              <span>RESILIENCE GUARD: ARMED</span>
            </span>
          )}
        </div>
      </div>

      {/* Recovery Flow Stepper */}
      <div className="p-4 rounded-xl bg-surface-container-low border border-outline-variant/20 flex flex-col gap-3">
        <div className="flex items-center justify-between text-xs font-semibold text-on-surface">
          <span>Guaranteed Recovery Architecture</span>
          <span className="text-[11px] font-mono text-emerald-400">0 Events Lost · 0 Duplicates</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-5 gap-2 text-center text-xs font-mono">
          <div className={`p-2.5 rounded-lg border transition-all ${
            isPausedOrInterrupted ? 'bg-amber-500/10 border-amber-500/40 text-amber-300' : 'bg-surface-container border-outline-variant/15 text-on-surface-variant'
          }`}>
            <span className="text-[10px] block opacity-60">1. Fault</span>
            <span className="font-bold">Worker Interrupted</span>
          </div>

          <div className={`p-2.5 rounded-lg border transition-all ${
            lastCheckpointPk ? 'bg-primary/15 border-primary/40 text-primary' : 'bg-surface-container border-outline-variant/15 text-on-surface-variant'
          }`}>
            <span className="text-[10px] block opacity-60">2. Checkpoint</span>
            <span className="font-bold">PK {lastCheckpointPk ? lastCheckpointPk.toLocaleString() : 'Preserved'}</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/15 text-on-surface-variant">
            <span className="text-[10px] block opacity-60">3. Restart</span>
            <span className="font-bold">Worker Re-Spawned</span>
          </div>

          <div className="p-2.5 rounded-lg bg-surface-container border border-outline-variant/15 text-on-surface-variant">
            <span className="text-[10px] block opacity-60">4. Catchup</span>
            <span className="font-bold">Replay Stream</span>
          </div>

          <div className="p-2.5 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
            <span className="text-[10px] block opacity-60">5. Guarantee</span>
            <span className="font-bold">✓ 100% Parity</span>
          </div>
        </div>
      </div>

      {/* Action Buttons Grid */}
      <div className="flex flex-col gap-3">
        <span className="text-xs font-mono font-semibold text-on-surface-variant uppercase tracking-wider">
          Inject Chaos Faults:
        </span>

        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
          <button
            onClick={() => handleInject('KILL_BACKFILL_WORKER')}
            disabled={isSubmitting || state === 'COMPLETED'}
            className="p-3 rounded-xl bg-surface-container-high/60 hover:bg-amber-500/15 border border-outline-variant/30 hover:border-amber-500/40 text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group"
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-on-surface group-hover:text-amber-300">
              <span className="material-symbols-outlined text-[18px] text-amber-400">cancel</span>
              <span>Kill Backfill Worker</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Halts backfill mid-batch. Tests atomic PK checkpoint in Redis.
            </p>
          </button>

          <button
            onClick={() => handleInject('KILL_WAL_READER')}
            disabled={isSubmitting || state === 'COMPLETED'}
            className="p-3 rounded-xl bg-surface-container-high/60 hover:bg-amber-500/15 border border-outline-variant/30 hover:border-amber-500/40 text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group"
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-on-surface group-hover:text-amber-300">
              <span className="material-symbols-outlined text-[18px] text-amber-400">link_off</span>
              <span>Kill WAL Reader</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Interrupts CDC replication connection without slot deletion.
            </p>
          </button>

          <button
            onClick={() => handleInject('KILL_CHANGE_APPLIER')}
            disabled={isSubmitting || state === 'COMPLETED'}
            className="p-3 rounded-xl bg-surface-container-high/60 hover:bg-amber-500/15 border border-outline-variant/30 hover:border-amber-500/40 text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group"
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-on-surface group-hover:text-amber-300">
              <span className="material-symbols-outlined text-[18px] text-amber-400">pause_circle</span>
              <span>Kill Change Applier</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Pauses dual-write applier. Tests Kafka buffer persistence.
            </p>
          </button>

          <button
            onClick={() => handleInject('INJECT_LATENCY_2S')}
            disabled={isSubmitting || state === 'COMPLETED'}
            className="p-3 rounded-xl bg-surface-container-high/60 hover:bg-primary/15 border border-outline-variant/30 hover:border-primary/40 text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group"
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-on-surface group-hover:text-primary">
              <span className="material-symbols-outlined text-[18px] text-primary">timer</span>
              <span>Inject 2s Latency</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Simulates extreme database disk I/O bottleneck & network delay.
            </p>
          </button>

          <button
            onClick={() => handleInject('PAUSE_KAFKA')}
            disabled={isSubmitting || state === 'COMPLETED'}
            className="p-3 rounded-xl bg-surface-container-high/60 hover:bg-primary/15 border border-outline-variant/30 hover:border-primary/40 text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group"
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-on-surface group-hover:text-primary">
              <span className="material-symbols-outlined text-[18px] text-primary">traffic</span>
              <span>Pause Kafka Ingress</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Tests backpressure handling and CDC message accumulation.
            </p>
          </button>

          {/* Recovery Button */}
          <button
            onClick={handleRecover}
            disabled={isSubmitting || state === 'COMPLETED'}
            className={`p-3 rounded-xl border text-left transition-all disabled:opacity-50 cursor-pointer flex flex-col gap-1 group ${
              isPausedOrInterrupted
                ? 'bg-emerald-500/20 border-emerald-500/50 shadow-md animate-pulse'
                : 'bg-surface-container-high/60 hover:bg-emerald-500/15 border-outline-variant/30 hover:border-emerald-500/40'
            }`}
          >
            <div className="flex items-center gap-2 font-semibold text-xs text-emerald-400">
              <span className="material-symbols-outlined text-[18px]">restart_alt</span>
              <span>Self-Healing Recovery</span>
            </div>
            <p className="text-[11px] text-on-surface-variant">
              Restart worker from last committed checkpoint with 0-loss guarantee.
            </p>
          </button>
        </div>
      </div>

      {/* Feedback Alert */}
      {feedback && (
        <div className={`p-3 rounded-xl text-xs font-mono border flex items-center gap-2.5 ${
          feedback.type === 'success'
            ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'
            : 'bg-error-container/20 text-error border-error/30'
        }`}>
          <span className="material-symbols-outlined text-[18px]">
            {feedback.type === 'success' ? 'check_circle' : 'error'}
          </span>
          <span>{feedback.message}</span>
        </div>
      )}

      {/* Real-time Resilience Audit Log */}
      {resilienceEvents.length > 0 && (
        <div className="flex flex-col gap-2">
          <span className="text-[11px] font-mono text-on-surface-variant uppercase tracking-wider">
            Resilience Incident & Recovery Audit Log
          </span>
          <div className="max-h-44 overflow-y-auto rounded-xl bg-surface-container-lowest border border-outline-variant/20 p-3 font-mono text-xs space-y-1.5 text-on-surface-variant">
            {resilienceEvents.map((evt, i) => (
              <div key={i} className="flex items-start gap-2">
                <span className="text-primary select-none">&gt;</span>
                <span className={evt.includes('Self-Healing') || evt.includes('Success') ? 'text-emerald-400 font-semibold' : ''}>
                  {evt}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
