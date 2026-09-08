package com.safemigrate.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Represents a single change event captured from the PostgreSQL Write-Ahead Log (WAL).
 */
public record WalChangeEvent(
        String table,
        OperationType operation,
        Map<String, Object> oldValues,
        Map<String, Object> newValues,
        long lsn,
        Instant timestamp
) implements Serializable {

    public WalChangeEvent {
        oldValues = oldValues != null ? Collections.unmodifiableMap(oldValues) : Collections.emptyMap();
        newValues = newValues != null ? Collections.unmodifiableMap(newValues) : Collections.emptyMap();
    }

    /**
     * Retrieves the primary key value from newValues (or oldValues for DELETE).
     */
    public Object getPrimaryKeyValue(String pkColumn) {
        if (operation == OperationType.DELETE) {
            return oldValues.get(pkColumn);
        }
        return newValues.get(pkColumn);
    }
}
