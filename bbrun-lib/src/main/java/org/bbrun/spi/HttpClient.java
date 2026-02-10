package org.bbrun.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract HTTP client interface.
 * Implementations wrap specific HTTP libraries (OkHttp, Java HttpClient, etc.)
 */
public interface HttpClient extends AutoCloseable {

    /**
     * Execute an HTTP request synchronously.
     */
    HttpResponse execute(HttpRequest request);

    /**
     * Execute an HTTP request asynchronously.
     */
    CompletableFuture<HttpResponse> executeAsync(HttpRequest request);

    /**
     * HTTP request representation.
     */
    record HttpRequest(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body) {
        public static HttpRequest get(String url, Map<String, String> headers) {
            return new HttpRequest("GET", url, headers, null);
        }

        public static HttpRequest post(String url, Map<String, String> headers, byte[] body) {
            return new HttpRequest("POST", url, headers, body);
        }

        public static HttpRequest put(String url, Map<String, String> headers, byte[] body) {
            return new HttpRequest("PUT", url, headers, body);
        }

        public static HttpRequest patch(String url, Map<String, String> headers, byte[] body) {
            return new HttpRequest("PATCH", url, headers, body);
        }

        public static HttpRequest delete(String url, Map<String, String> headers) {
            return new HttpRequest("DELETE", url, headers, null);
        }
    }

    /**
     * HTTP response representation.
     */
    record HttpResponse(
            int status,
            Map<String, String> headers,
            byte[] body,
            long durationMs) {
        public String bodyAsString() {
            return body != null ? new String(body) : null;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    @Override
    default void close() {
        // Default no-op, implementations can override
    }
}
