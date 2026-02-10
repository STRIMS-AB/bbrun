package org.bbrun;

/**
 * Result of a completed script execution.
 */
public record ExecutionResult(
        boolean success,
        int totalStatements,
        int passedAssertions,
        int failedAssertions,
        java.util.List<Warning> warnings,
        java.util.List<RequestMetric> requests,
        long durationMs,
        Throwable error) {
    public boolean isSuccess() {
        return success && error == null;
    }

    public static ExecutionResult success(
            int totalStatements,
            int passedAssertions,
            java.util.List<Warning> warnings,
            java.util.List<RequestMetric> requests,
            long durationMs) {
        return new ExecutionResult(
                true, totalStatements, passedAssertions, 0,
                warnings, requests, durationMs, null);
    }

    public static ExecutionResult failure(
            int totalStatements,
            int passedAssertions,
            int failedAssertions,
            java.util.List<Warning> warnings,
            java.util.List<RequestMetric> requests,
            long durationMs,
            Throwable error) {
        return new ExecutionResult(
                false, totalStatements, passedAssertions, failedAssertions,
                warnings, requests, durationMs, error);
    }
}
