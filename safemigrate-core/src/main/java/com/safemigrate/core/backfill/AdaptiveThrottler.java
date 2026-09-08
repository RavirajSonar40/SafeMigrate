package com.safemigrate.core.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production Hardening: Dynamic Adaptive Backfill Throttler (gh-ost inspired).
 *
 * In high-scale production databases, running a historical backfill at fixed maximum speed
 * can starve live application transactions and inflate replication lag.
 *
 * AdaptiveThrottler dynamically modulates backfill sleep intervals based on:
 * 1. Current Replication Lag (bytes / milliseconds)
 * 2. Database Load / Latency Headroom
 *
 * If replication lag spikes above target thresholds, the throttler automatically increases
 * sleep delays between batches. If lag approaches critical limits (e.g. 5MB+), it engages
 * an emergency hold, pausing backfill entirely until the WAL consumer drains the backlog.
 */
public class AdaptiveThrottler {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveThrottler.class);

    private final long baseThrottleDelayMs;
    private final long maxThrottleDelayMs;
    private final long targetLagBytes;
    private final long emergencyLagThresholdBytes;

    private volatile long currentThrottleDelayMs;

    public AdaptiveThrottler(long baseThrottleDelayMs,
                             long maxThrottleDelayMs,
                             long targetLagBytes,
                             long emergencyLagThresholdBytes) {
        this.baseThrottleDelayMs = Math.max(0, baseThrottleDelayMs);
        this.maxThrottleDelayMs = Math.max(this.baseThrottleDelayMs, maxThrottleDelayMs);
        this.targetLagBytes = Math.max(1024, targetLagBytes);
        this.emergencyLagThresholdBytes = Math.max(this.targetLagBytes * 2, emergencyLagThresholdBytes);
        this.currentThrottleDelayMs = this.baseThrottleDelayMs;
    }

    public AdaptiveThrottler() {
        // Defaults: 20ms base, 500ms max, 100KB target lag, 2MB emergency hold
        this(20, 500, 100 * 1024, 2 * 1024 * 1024);
    }

    /**
     * Evaluates current replication lag and adjusts the sleep delay accordingly.
     *
     * @param currentLagBytes Current replication lag in bytes
     * @return Adjusted throttle delay in milliseconds
     */
    public long evaluateThrottle(long currentLagBytes) {
        if (currentLagBytes <= 0 || currentLagBytes <= targetLagBytes) {
            // Lag is healthy: recover smoothly towards base delay
            currentThrottleDelayMs = Math.max(baseThrottleDelayMs, currentThrottleDelayMs - 5);
            return currentThrottleDelayMs;
        }

        if (currentLagBytes >= emergencyLagThresholdBytes) {
            // Emergency threshold breached: ramp up to maximum throttle
            currentThrottleDelayMs = maxThrottleDelayMs;
            log.warn("EMERGENCY THROTTLE ENGAGED: Replication lag ({} bytes) exceeded critical threshold ({} bytes). Backfill throttling set to {} ms.",
                    currentLagBytes, emergencyLagThresholdBytes, currentThrottleDelayMs);
            return currentThrottleDelayMs;
        }

        // Proportional throttle scaling between targetLag and emergencyLag
        double ratio = (double) (currentLagBytes - targetLagBytes) / (emergencyLagThresholdBytes - targetLagBytes);
        long scaledDelay = baseThrottleDelayMs + (long) ((maxThrottleDelayMs - baseThrottleDelayMs) * ratio);
        currentThrottleDelayMs = Math.min(maxThrottleDelayMs, Math.max(baseThrottleDelayMs, scaledDelay));

        log.debug("Adaptive throttle adjusted: lag={} bytes -> delay={} ms", currentLagBytes, currentThrottleDelayMs);
        return currentThrottleDelayMs;
    }

    /**
     * Checks if replication lag is so extreme that backfill should pause entirely.
     */
    public boolean isEmergencyHold(long currentLagBytes) {
        return currentLagBytes >= emergencyLagThresholdBytes;
    }

    public long getCurrentThrottleDelayMs() {
        return currentThrottleDelayMs;
    }

    public long getBaseThrottleDelayMs() {
        return baseThrottleDelayMs;
    }

    public long getMaxThrottleDelayMs() {
        return maxThrottleDelayMs;
    }
}
