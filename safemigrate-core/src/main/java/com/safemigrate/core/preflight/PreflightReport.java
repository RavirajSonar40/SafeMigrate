package com.safemigrate.core.preflight;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the results of all pre-flight inspections performed before initiating a schema migration.
 */
public record PreflightReport(
        String tableName,
        boolean passed,
        List<PreflightIssue> issues,
        String primaryKeyColumn,
        boolean replicaIdentityFull,
        long tableSizeBytes,
        long estimatedRequiredDiskBytes,
        long availableDiskBytes,
        boolean activeLockContention
) {

    public PreflightReport {
        issues = (issues != null) ? List.copyOf(issues) : Collections.emptyList();
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == PreflightIssue.Severity.ERROR);
    }

    public boolean hasWarnings() {
        return issues.stream().anyMatch(i -> i.severity() == PreflightIssue.Severity.WARNING);
    }

    public List<PreflightIssue> getErrors() {
        return issues.stream().filter(i -> i.severity() == PreflightIssue.Severity.ERROR).toList();
    }

    public List<PreflightIssue> getWarnings() {
        return issues.stream().filter(i -> i.severity() == PreflightIssue.Severity.WARNING).toList();
    }
}
