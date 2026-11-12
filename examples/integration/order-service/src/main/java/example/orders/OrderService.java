package example.orders;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bbrun.IntegrationEngine;
import org.bbrun.interpreter.ExecutionResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP adapter: route, construct invocation inputs, execute a script, serialize its value. */
public final class OrderService implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final DemoUpstreams upstreams;
    private final IntegrationEngine engine;
    private final Path operations;

    public OrderService(Path project, int port) throws IOException {
        upstreams = new DemoUpstreams();
        try {
            engine = IntegrationEngine.fromConfig(project.resolve("bbrun.yaml"))
                    .resource(IntegrationEngine.httpRequest("catalogApi", upstreams.baseUrl() + "/catalog"))
                    .resource(IntegrationEngine.httpRequest("ordersApi", upstreams.baseUrl() + "/orders"))
                    .resource(IntegrationEngine.httpRequest("notificationsApi", upstreams.baseUrl() + "/notifications"))
                    .build();
            operations = project.resolve("operations");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException | RuntimeException e) {
            upstreams.close();
            executor.shutdownNow();
            throw e;
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int storedOrderCount() { return upstreams.orderCount(); }
    java.util.List<Object> notifications() { return upstreams.messages(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] path = exchange.getRequestURI().getPath().split("/");
            String method = exchange.getRequestMethod();
            String operation = null;
            Map<String, Object> pathParameters = new LinkedHashMap<>();
            if (method.equals("GET") && path.length == 3 && path[1].equals("products")) {
                operation = "get-product";
                pathParameters.put("id", path[2]);
            } else if (method.equals("POST") && path.length == 2 && path[1].equals("orders")) {
                operation = "place-order";
            } else if (method.equals("GET") && path.length == 3 && path[1].equals("orders")) {
                operation = "get-order";
                pathParameters.put("id", path[2]);
            } else if (method.equals("POST") && path.length == 4 && path[1].equals("orders")
                    && path[3].equals("confirmation")) {
                operation = "request-confirmation";
                pathParameters.put("id", path[2]);
            }
            if (operation == null) {
                HttpJson.send(exchange, 404, Map.of("code", "ROUTE_NOT_FOUND"));
                return;
            }

            // Demo identity adapter only. A real host obtains this from its authenticated principal.
            String customerId = exchange.getRequestHeaders().getFirst("X-Customer-Id");
            if (customerId == null || customerId.isBlank()) {
                HttpJson.send(exchange, 401, Map.of("code", "UNAUTHENTICATED"));
                return;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("method", method);
            request.put("path", pathParameters);
            request.put("body", HttpJson.read(exchange));
            String correlationId = UUID.randomUUID().toString();
            Map<String, Object> context = Map.of("customerId", customerId, "correlationId", correlationId);

            ExecutionResult result = engine.execute(operations.resolve(operation + ".bbrun"), request, context);
            exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
            if (result.responseHeaders() != null) {
                result.responseHeaders().forEach(exchange.getResponseHeaders()::set);
            }
            if (!result.isSuccess()) {
                System.err.println("Operation " + operation + " failed: " + result.errorMessage());
                HttpJson.send(exchange, 500, Map.of("code", "INTEGRATION_FAILED"));
            } else if (result.statusCode() != null) {
                // The script chose a status via status(404) / status(201) ...
                HttpJson.send(exchange, result.statusCode(), result.value());
            } else if (result.value() == null) {
                // No return value: 204 via host default.
                HttpJson.send(exchange, 204, null);
            } else {
                // A returned value defaults to 200.
                HttpJson.send(exchange, 200, result.value());
            }
        } catch (JsonParseException e) {
            HttpJson.send(exchange, 400, Map.of("code", "INVALID_JSON"));
        } catch (RuntimeException e) {
            System.err.println("Request failed: " + e.getMessage());
            HttpJson.send(exchange, 500, Map.of("code", "INTEGRATION_FAILED"));
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        upstreams.close();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        OrderService service = new OrderService(Path.of("."), port);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        System.out.println("Order API: " + service.baseUrl() + " (demo caller header: X-Customer-Id)");
    }
}
