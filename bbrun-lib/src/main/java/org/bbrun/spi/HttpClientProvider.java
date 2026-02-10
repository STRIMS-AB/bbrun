package org.bbrun.spi;

import org.bbrun.interpreter.ExecutionOptions;

/**
 * SPI for pluggable HTTP client implementations.
 * 
 * <p>
 * Default implementation uses OkHttp. Future implementations could provide:
 * <ul>
 * <li>Java HttpClient (Java 11+)</li>
 * <li>HTTP/3 support (future Java versions)</li>
 * <li>Custom implementations for testing</li>
 * </ul>
 * 
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 * The provider with the highest {@link #priority()} is selected.
 */
public interface HttpClientProvider {

    /**
     * Create an HTTP client instance.
     */
    HttpClient create(ExecutionOptions options);

    /**
     * Provider priority. Higher values take precedence.
     * Default OkHttp implementation uses priority 0.
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
}
