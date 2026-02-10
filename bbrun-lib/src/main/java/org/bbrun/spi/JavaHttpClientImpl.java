package org.bbrun.spi;

import org.bbrun.interpreter.ExecutionOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default HTTP client implementation using Java 11+ HttpClient.
 * Zero external dependencies.
 */
public class JavaHttpClientImpl implements HttpClient {

    private final java.net.http.HttpClient client;
    private final Duration timeout;

    public JavaHttpClientImpl(ExecutionOptions options) {
        this.timeout = options.getTimeout();
        this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        long start = System.currentTimeMillis();
        try {
            java.net.http.HttpRequest httpRequest = buildRequest(request);
            java.net.http.HttpResponse<byte[]> response = client.send(httpRequest, BodyHandlers.ofByteArray());
            return toHttpResponse(response, System.currentTimeMillis() - start);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
        long start = System.currentTimeMillis();
        java.net.http.HttpRequest httpRequest = buildRequest(request);

        return client.sendAsync(httpRequest, BodyHandlers.ofByteArray())
                .thenApply(response -> toHttpResponse(response, System.currentTimeMillis() - start));
    }

    @Override
    public void close() {
        // Java HttpClient doesn't require explicit cleanup
    }

    private java.net.http.HttpRequest buildRequest(HttpRequest request) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(timeout);

        // Add headers
        if (request.headers() != null) {
            request.headers().forEach(builder::header);
        }

        // Set method and body
        BodyPublisher bodyPublisher = request.body() != null
                ? BodyPublishers.ofByteArray(request.body())
                : BodyPublishers.noBody();

        switch (request.method().toUpperCase()) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(bodyPublisher);
            case "PUT" -> builder.PUT(bodyPublisher);
            case "PATCH" -> builder.method("PATCH", bodyPublisher);
            case "DELETE" -> builder.DELETE();
            default -> throw new IllegalArgumentException("Unknown method: " + request.method());
        }

        return builder.build();
    }

    private HttpResponse toHttpResponse(java.net.http.HttpResponse<byte[]> response, long durationMs) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });

        return new HttpResponse(response.statusCode(), headers, response.body(), durationMs);
    }
}
