package org.bbrun.load;

/**
 * A single iteration captured by {@link LoadTestRunner}.
 */
public record LoadSample(
        long epochMillis,
        long latencyMs,
        boolean success,
        int requests,
        String error
) {}