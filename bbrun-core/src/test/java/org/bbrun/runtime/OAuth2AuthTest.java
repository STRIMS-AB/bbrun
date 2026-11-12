package org.bbrun.runtime;

import com.sun.net.httpserver.HttpServer;
import org.bbrun.config.ResourceAuth;
import org.bbrun.config.ResourceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class OAuth2AuthTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger apiRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/oauth/token", exchange -> {
            tokenRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = "{\"access_token\": \"tok_abc\", \"expires_in\": 3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/api/orders/1", exchange -> {
            apiRequests.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            assertEquals("Bearer tok_abc", auth);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = "{\"id\": \"1\", \"sku\": \"WIDGET-A\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void testOAuth2TokenAcquiredAndCached() {
        ResourceAuth auth = ResourceAuth.oauth2(baseUrl + "/oauth/token", "client-1", "secret-1", "orders.read");
        ResourceDefinition def = new ResourceDefinition("orderApi", null, baseUrl + "/api", null, auth);
        HttpResourceHandler handler = new HttpResourceHandler(def);

        ResponseObject r1 = handler.execute("get", List.of("#orders/1"), null);
        assertEquals(200, r1.status());

        ResponseObject r2 = handler.execute("get", List.of("#orders/1"), null);
        assertEquals(200, r2.status());

        // Token fetched exactly once, then cached across both calls
        assertEquals(1, tokenRequests.get());
        assertEquals(2, apiRequests.get());
    }

    @Test
    void testOAuth2RequiresCredentials() {
        ResourceAuth auth = ResourceAuth.oauth2(baseUrl + "/oauth/token", null, null, null);
        ResourceDefinition def = new ResourceDefinition("orderApi", null, baseUrl + "/api", null, auth);
        HttpResourceHandler handler = new HttpResourceHandler(def);

        assertThrows(RuntimeException.class, () -> handler.execute("get", List.of("#orders/1"), null));
    }
}