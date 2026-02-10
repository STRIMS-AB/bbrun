package org.bbrun.spi;

import org.bbrun.interpreter.ExecutionOptions;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Registry that discovers and provides SPI implementations.
 * Uses ServiceLoader to find the best available provider.
 */
public final class ProviderRegistry {

    private static final Logger LOG = Logger.getLogger(ProviderRegistry.class.getName());

    private static volatile ProviderRegistry instance;

    private final HttpClientProvider httpClientProvider;
    private final ExecutorProvider executorProvider;

    private ProviderRegistry() {
        this.httpClientProvider = discoverHttpClientProvider();
        this.executorProvider = discoverExecutorProvider();

        LOG.info(() -> "BBRun SPI: HTTP client = " + httpClientProvider.name() +
                ", Executor = " + executorProvider.name());
    }

    public static ProviderRegistry getInstance() {
        if (instance == null) {
            synchronized (ProviderRegistry.class) {
                if (instance == null) {
                    instance = new ProviderRegistry();
                }
            }
        }
        return instance;
    }

    public HttpClient createHttpClient(ExecutionOptions options) {
        return httpClientProvider.create(options);
    }

    public ExecutorService createExecutor(ExecutionOptions options) {
        return executorProvider.create(options);
    }

    public String getHttpClientName() {
        return httpClientProvider.name();
    }

    public String getExecutorName() {
        return executorProvider.name();
    }

    private HttpClientProvider discoverHttpClientProvider() {
        return ServiceLoader.load(HttpClientProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(HttpClientProvider::priority))
                .orElseGet(DefaultHttpClientProvider::new);
    }

    private ExecutorProvider discoverExecutorProvider() {
        return ServiceLoader.load(ExecutorProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(ExecutorProvider::isAvailable)
                .max(Comparator.comparingInt(ExecutorProvider::priority))
                .orElseGet(DefaultExecutorProvider::new);
    }

    /**
     * Default HTTP client provider using Java 11+ HttpClient.
     * Zero external dependencies.
     */
    private static class DefaultHttpClientProvider implements HttpClientProvider {
        @Override
        public HttpClient create(ExecutionOptions options) {
            return new JavaHttpClientImpl(options);
        }

        @Override
        public String name() {
            return "Java HttpClient (default)";
        }
    }

    /**
     * Default executor provider using cached thread pool.
     */
    private static class DefaultExecutorProvider implements ExecutorProvider {
        @Override
        public ExecutorService create(ExecutionOptions options) {
            return Executors.newCachedThreadPool();
        }

        @Override
        public String name() {
            return "CachedThreadPool (default)";
        }
    }
}
