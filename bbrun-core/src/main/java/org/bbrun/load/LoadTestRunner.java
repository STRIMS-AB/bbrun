package org.bbrun.load;

import org.bbrun.BBRun;
import org.bbrun.interpreter.ExecutionResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs a BBRun script concurrently from {@code users} virtual threads, either
 * for a fixed number of iterations, a fixed duration, or both (whichever ends
 * first).  All workers share a single {@link BBRun} engine; each iteration gets
 * a fresh interpreter context, so concurrent execution is safe.
 *
 * <p>Progress can be observed live via {@link #snapshot()} while
 * {@link #run()} is executing (a reporter thread polls it).</p>
 */
public class LoadTestRunner {

    private static final int RECENT_WINDOW = 1000;
    private static final long WORKER_GRACE_MS = 3_600_000L;

    private final BBRun engine;
    private final LoadTestConfig config;

    private final ConcurrentLinkedQueue<LoadSample> samples = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<LoadSample> recent = new ArrayDeque<>();

    private final AtomicLong globalIterations = new AtomicLong();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder latencySumMs = new LongAdder();

    private volatile long startNanos = System.nanoTime();
    private volatile boolean stop;

    public LoadTestRunner(BBRun engine, LoadTestConfig config) {
        this.engine = engine;
        this.config = config;
    }

    /**
     * Runs the load test to completion and returns the aggregated report.
     * Blocks until every worker has finished.
     */
    public LoadTestReport run() {
        samples.clear();
        synchronized (recent) {
            recent.clear();
        }
        globalIterations.set(0);
        succeeded.reset();
        failed.reset();
        totalRequests.reset();
        latencySumMs.reset();
        stop = false;

        startNanos = System.nanoTime();
        long deadlineNanos = config.duration() != null
                ? startNanos + config.duration().toNanos()
                : Long.MAX_VALUE;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.users(); i++) {
                final int index = i;
                pool.submit(() -> worker(index, deadlineNanos));
            }
            pool.shutdown();
            long grace = (config.duration() != null
                    ? config.duration().toMillis()
                    : 0) + WORKER_GRACE_MS;
            try {
                if (!pool.awaitTermination(grace, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        List<LoadSample> all = new ArrayList<>(samples);
        all.sort(Comparator.comparingLong(LoadSample::epochMillis));
        return LoadTestReport.fromSamples(elapsedMs, config.users(), all);
    }

    /**
     * Rolling snapshot of the run so far, safe to call from another thread
     * while {@link #run()} is executing.
     */
    public LiveStats snapshot() {
        long total = succeeded.sum() + failed.sum();

        List<Long> latencies = new ArrayList<>();
        synchronized (recent) {
            for (LoadSample s : recent) {
                latencies.add(s.latencyMs());
            }
        }
        latencies.sort(null);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new LiveStats(
                elapsedMs,
                total,
                succeeded.sum(),
                failed.sum(),
                totalRequests.sum(),
                total == 0 ? 0 : (latencySumMs.sum() / (double) total),
                percentile(latencies, 50),
                percentile(latencies, 95),
                percentile(latencies, 99));
    }

    private void worker(int index, long deadlineNanos) {
        if (config.rampUp() != null && config.rampUp().toNanos() > 0) {
            long lead = config.rampUp().toNanos() * index / config.users();
            sleepNanos(lead);
        }

        while (!stop && System.nanoTime() < deadlineNanos) {
            long target = config.iterations();
            if (target > 0 && globalIterations.incrementAndGet() > target) {
                break;
            }

            long begin = System.nanoTime();
            try {
                ExecutionResult result = engine.execute(config.script());
                long latency = (System.nanoTime() - begin) / 1_000_000;
                record(new LoadSample(System.currentTimeMillis(), latency, result.isSuccess(),
                        result.requestCount(), result.isSuccess() ? null : result.errorMessage()));
            } catch (Exception e) {
                long latency = (System.nanoTime() - begin) / 1_000_000;
                record(new LoadSample(System.currentTimeMillis(), latency, false, 0,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
    }

    private void record(LoadSample sample) {
        samples.add(sample);
        synchronized (recent) {
            recent.addLast(sample);
            while (recent.size() > RECENT_WINDOW) {
                recent.removeFirst();
            }
        }
        if (sample.success()) {
            succeeded.increment();
        } else {
            failed.increment();
        }
        totalRequests.add(sample.requests());
        latencySumMs.add(sample.latencyMs());
    }

    private static void sleepNanos(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) break;
            try {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Math.max(1, remaining)), (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static double percentile(List<Long> latencies, double p) {
        if (latencies.isEmpty()) return 0;
        double index = (p / 100.0) * (latencies.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return latencies.get(lower);
        double frac = index - lower;
        return latencies.get(lower) + frac * (latencies.get(upper) - latencies.get(lower));
    }
}