package example.orders;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class HttpJson {
    private static final Gson GSON = new Gson();

    private HttpJson() {}

    static Object read(HttpExchange exchange) throws IOException {
        String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : GSON.fromJson(text, Object.class);
    }

    static void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
