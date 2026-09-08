package com.safemigrate.server.service;

import com.safemigrate.core.state.MigrationState;
import com.safemigrate.server.dto.MigrationProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MigrationSseService {

    private static final Logger log = LoggerFactory.getLogger(MigrationSseService.class);
    private static final Long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    private final Map<String, List<SseEmitter>> emittersByMigration = new ConcurrentHashMap<>();

    public SseEmitter registerEmitter(String migrationId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        List<SseEmitter> list = emittersByMigration.computeIfAbsent(migrationId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed for migration: {}", migrationId);
            list.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out for migration: {}", migrationId);
            list.remove(emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE emitter error for migration: {}: {}", migrationId, e.getMessage());
            list.remove(emitter);
        });

        // Send initial connect event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "migrationId", migrationId,
                            "timestamp", System.currentTimeMillis(),
                            "status", "CONNECTED"
                    )));
        } catch (IOException e) {
            log.warn("Failed to send initial SSE connect event: {}", e.getMessage());
            list.remove(emitter);
        }

        return emitter;
    }

    public void broadcastProgress(String migrationId, MigrationProgressEvent event) {
        List<SseEmitter> list = emittersByMigration.get(migrationId);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event));
            } catch (Exception e) {
                log.debug("Removing failed SSE emitter for migration {}: {}", migrationId, e.getMessage());
                list.remove(emitter);
            }
        }
    }

    public void broadcastStatusChange(String migrationId, MigrationState state, String message) {
        List<SseEmitter> list = emittersByMigration.get(migrationId);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of(
                                "migrationId", migrationId,
                                "state", state.name(),
                                "message", message != null ? message : "",
                                "timestamp", System.currentTimeMillis()
                        )));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    public void broadcastError(String migrationId, String error) {
        List<SseEmitter> list = emittersByMigration.get(migrationId);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of(
                                "migrationId", migrationId,
                                "error", error != null ? error : "Unknown error",
                                "timestamp", System.currentTimeMillis()
                        )));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    public void completeStream(String migrationId) {
        List<SseEmitter> list = emittersByMigration.remove(migrationId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
