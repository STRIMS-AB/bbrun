package example.orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local stand-ins for dependencies. They implement no public Order API logic. */
final class DemoUpstreams implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Object> records = new ConcurrentHashMap<>();
    private final List<Object> messages = new CopyOnWriteArrayList<>();

    DemoUpstreams() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int orderCount() { return records.size(); }
    List<Object> messages() { return List.copyOf(messages); }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (method.equals("GET") && path.equals("/catalog/products/P100")) {
            HttpJson.send(exchange, 200, Map.of(
                    "sku", "P100", "label", "Travel mug", "price_cents", 1250, "currency", "eur"));
        } else if (method.equals("GET") && path.equals("/catalog/products/UNAVAILABLE")) {
            HttpJson.send(exchange, 503, Map.of("error", "catalog maintenance"));
        } else if (method.equals("GET") && path.equals("/catalog/products/BROKEN")) {
            // Dependency contract fault for the integration tests: a missing price.
            HttpJson.send(exchange, 200, Map.of("sku", "BROKEN", "label", "Bad data", "currency", "eur"));
        } else if (method.equals("POST") && path.equals("/orders/records")) {
            Object body = HttpJson.read(exchange);
            if (!(body instanceof Map<?, ?> record) || !(record.get("id") instanceof String id)) {
                HttpJson.send(exchange, 400, Map.of("error", "id required"));
                return;
            }
            records.put(id, body);
            HttpJson.send(exchange, 201, body);
        } else if (method.equals("GET") && path.startsWith("/orders/records/")) {
            Object record = records.get(path.substring("/orders/records/".length()));
            HttpJson.send(exchange, record == null ? 404 : 200,
                    record == null ? Map.of("error", "not found") : record);
        } else if (method.equals("POST") && path.equals("/notifications/messages")) {
            messages.add(HttpJson.read(exchange));
            HttpJson.send(exchange, 202, Map.of("accepted", true));
        } else {
            HttpJson.send(exchange, 404, Map.of("error", "not found"));
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
