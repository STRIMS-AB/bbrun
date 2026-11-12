package org.bbrun.load;

import java.util.List;

/**
 * Final aggregated result of a completed {@link LoadTestRunner} run.
 */
public record LoadTestReport(
        long elapsedMs,
        int users,
        long total,
        long succeeded,
        long failed,
        long totalRequests,
        double avgLatencyMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double maxLatencyMs,
        double iterationsPerSec,
        double requestsPerSec,
        List<LoadSample> samples
) {

    public double successRate() {
        return total == 0 ? 0 : (succeeded * 100.0) / total;
    }

    public static LoadTestReport fromSamples(long elapsedMs, int users, List<LoadSample> samples) {
        if (samples.isEmpty()) {
            return new LoadTestReport(elapsedMs, users, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        long succeeded = 0;
        long failed = 0;
        long totalRequests = 0;
        long latencySum = 0;
        long maxLatency = 0;

        long[] latencies = new long[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            LoadSample s = samples.get(i);
            latencies[i] = s.latencyMs();
            if (s.success()) succeeded++; else failed++;
            totalRequests += s.requests();
            latencySum += s.latencyMs();
            if (s.latencyMs() > maxLatency) maxLatency = s.latencyMs();
        }

        java.util.Arrays.sort(latencies);
        long n = latencies.length;
        double avg = latencySum / (double) n;
        double p50 = percentile(latencies, 50);
        double p90 = percentile(latencies, 90);
        double p95 = percentile(latencies, 95);
        double p99 = percentile(latencies, 99);

        double secs = Math.max(1, elapsedMs) / 1000.0;

        return new LoadTestReport(
                elapsedMs, users, n, succeeded, failed, totalRequests,
                avg, p50, p90, p95, p99, maxLatency,
                n / secs, totalRequests / secs, List.copyOf(samples));
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double index = (p / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted[lower];
        double frac = index - lower;
        return sorted[lower] + frac * (sorted[upper] - sorted[lower]);
    }
}