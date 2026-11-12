package org.bbrun.load;

/**
 * Point-in-time view of an in-progress load test, used by the CLI progress
 * display.  Exposed via {@link LoadTestRunner#snapshot()}.
 */
public record LiveStats(
        long elapsedMs,
        long total,
        long succeeded,
        long failed,
        long requests,
        double avgLatencyMs,
        double p50Ms,
        double p95Ms,
        double p99Ms
) {

    public double successRate() {
        return total == 0 ? 0 : (succeeded * 100.0) / total;
    }

    public double iterationsPerSec() {
        return total == 0 ? 0 : (total * 1000.0) / Math.max(1, elapsedMs);
    }

    public double requestsPerSec() {
        return requests == 0 ? 0 : (requests * 1000.0) / Math.max(1, elapsedMs);
    }
}