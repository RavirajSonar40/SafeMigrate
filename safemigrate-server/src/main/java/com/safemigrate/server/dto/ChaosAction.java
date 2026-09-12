package com.safemigrate.server.dto;

public enum ChaosAction {
    KILL_WAL_READER,
    KILL_BACKFILL_WORKER,
    KILL_CHANGE_APPLIER,
    INJECT_LATENCY_2S,
    PAUSE_KAFKA
}
