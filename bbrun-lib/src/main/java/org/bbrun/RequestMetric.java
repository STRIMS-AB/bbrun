package org.bbrun;

/**
 * Metrics for an HTTP request.
 */
public record RequestMetric(
        String method,
        String path,
        int status,
        long durationMs,
        boolean success) {
}
