package example.orders;

import com.google.gson.Gson;
import org.bbrun.BBRun;
import org.bbrun.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {
    private static final Path PROJECT = Path.of("examples/integration/order-service");
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void testerScenariosCoverOneRunningBackendOverHttp() throws Exception {
        try (OrderService service = new OrderService(PROJECT, 0)) {
            Path suite = Path.of("examples/testing/order-api").toAbsolutePath();
            String yaml = Files.readString(suite.resolve("bbrun.yaml"))
                    .replace("${ORDER_API_URL:-http://127.0.0.1:8080}", service.baseUrl());
            BBRun runner = BBRun.builder().withConfig(ConfigLoader.parse(yaml, suite)).build();
            try (var scripts = Files.list(suite.resolve("tests"))) {
                for (Path script : scripts.filter(p -> p.toString().endsWith(".bbrun")).sorted().toList()) {
                    var result = runner.execute(script);
                    assertTrue(result.isSuccess(), script + ": " + result.errorMessage());
                    assertTrue(result.passedAssertions() > 0);
                }
            }
            assertEquals(2, service.storedOrderCount());
            assertEquals(1, service.notifications().size());
            Map<?, ?> message = (Map<?, ?>) service.notifications().get(0);
            assertEquals("customer-alice", message.get("customerId"));
            assertNotNull(message.get("correlationId"));
        }
    }

    @Test
    void serverContextControlsOwnershipAndSideEffects() throws Exception {
        try (OrderService service = new OrderService(PROJECT, 0)) {
            var created = send(service, "POST", "/orders", "alice",
                    "{\"productId\":\"P100\",\"quantity\":2,\"customerId\":\"mallory\",\"total\":0}");
            assertEquals(201, created.statusCode());
            Map<?, ?> order = GSON.fromJson(created.body(), Map.class);
            assertEquals("alice", order.get("customerId"));
            assertEquals(25.0, order.get("total"));
            String path = "/orders/" + order.get("id");
            assertEquals(path, created.headers().firstValue("Location").orElse(null));
            assertEquals(404, send(service, "GET", path, "bob", null).statusCode());
            assertEquals(404, send(service, "POST", path + "/confirmation", "bob", "{}").statusCode());
            assertTrue(service.notifications().isEmpty());
            assertEquals(204, send(service, "POST", path + "/confirmation", "alice", "{}").statusCode());
            assertEquals("alice", ((Map<?, ?>) service.notifications().get(0)).get("customerId"));
        }
    }

    @Test
    void dependencyFailureAndInvalidMappedDataDoNotCreateOrders() throws Exception {
        try (OrderService service = new OrderService(PROJECT, 0)) {
            var unavailable = send(service, "POST", "/orders", "alice",
                    "{\"productId\":\"UNAVAILABLE\",\"quantity\":1}");
            assertEquals(502, unavailable.statusCode());
            assertTrue(unavailable.body().contains("CATALOG_UNAVAILABLE"));
            var invalid = send(service, "POST", "/orders", "alice",
                    "{\"productId\":\"BROKEN\",\"quantity\":1}");
            assertEquals(500, invalid.statusCode());
            assertEquals(0, service.storedOrderCount());
        }
    }

    @Test
    void concurrentInvocationsDoNotShareRequestOrCallerContext() throws Exception {
        try (OrderService service = new OrderService(PROJECT, 0)) {
            var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 1; i <= 8; i++) {
                futures.add(client.sendAsync(HttpRequest.newBuilder(URI.create(service.baseUrl() + "/orders"))
                        .header("X-Customer-Id", "customer-" + i)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"productId\":\"P100\",\"quantity\":" + i + "}"))
                        .build(), HttpResponse.BodyHandlers.ofString()));
            }
            for (int i = 0; i < futures.size(); i++) {
                var response = futures.get(i).get();
                assertEquals(201, response.statusCode(), response.body());
                Map<?, ?> order = GSON.fromJson(response.body(), Map.class);
                assertEquals("customer-" + (i + 1), order.get("customerId"));
                assertEquals(12.5 * (i + 1), order.get("total"));
            }
        }
    }

    private HttpResponse<String> send(OrderService service, String method, String path,
                                      String customer, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(service.baseUrl() + path))
                .header("X-Customer-Id", customer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
