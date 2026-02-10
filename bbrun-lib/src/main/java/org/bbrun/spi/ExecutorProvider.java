package org.bbrun.spi;

import org.bbrun.interpreter.ExecutionOptions;

import java.util.concurrent.ExecutorService;

/**
 * SPI for pluggable executor implementations.
 * 
 * <p>
 * Default implementation uses cached thread pool (Java 17 compatible).
 * Java 21+ implementations can provide virtual threads.
 * 
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 * The provider with the highest {@link #priority()} is selected.
 */
public interface ExecutorProvider {

    /**
     * Create an executor service for async script execution.
     *
     * @param options execution options that may influence executor configuration
     * @return configured executor service
     */
    ExecutorService create(ExecutionOptions options);

    /**
     * Provider priority. Higher values take precedence.
     * Default cached thread pool uses priority 0.
     * Virtual thread implementation (Java 21+) would use priority 100.
     */
    default int priority() {
        return 0;
    }

    /**
     * Human-readable name for logging.
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Whether this provider is available in the current runtime.
     * Implementations should check Java version or feature availability.
     */
    default boolean isAvailable() {
        return true;
    }
}
