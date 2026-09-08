package com.safemigrate.core.model;

/**
 * SQL data mutation operations captured from the replication stream.
 */
public enum OperationType {
    INSERT,
    UPDATE,
    DELETE
}
