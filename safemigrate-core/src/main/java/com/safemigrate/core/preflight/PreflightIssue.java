package com.safemigrate.core.preflight;

/**
 * Represents an issue, warning, or failure discovered during pre-flight migration checks.
 */
public record PreflightIssue(Severity severity, String code, String message) {

    public enum Severity {
        ERROR,   // Critical blocker; migration must not start
        WARNING  // Advisory; migration may proceed with user acknowledgement
    }

    public static PreflightIssue error(String code, String message) {
        return new PreflightIssue(Severity.ERROR, code, message);
    }

    public static PreflightIssue warning(String code, String message) {
        return new PreflightIssue(Severity.WARNING, code, message);
    }
}
