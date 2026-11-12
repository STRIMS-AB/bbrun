package org.bbrun.runtime;

import com.alibaba.fastjson2.JSON;
import org.bbrun.config.ResourceDefinition;
import dev.iconn.api.Resource;
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.file.FileResource;
import dev.iconn.api.http.HttpRequest;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.queue.QueueResource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Universal bridge handler that dispatches operations from BBRun's execution model
 * directly to iconn's {@link Resource} implementations.
 */
public class IconnResourceHandler implements ResourceHandler {

    private final ResourceDefinition definition;
    private final Resource resource;
    private final AtomicLong emailIdCounter = new AtomicLong();

    public IconnResourceHandler(ResourceDefinition definition, Resource resource) {
        this.definition = definition;
        this.resource = resource;
    }

    @Override
    public ResourceDefinition getDefinition() {
        return definition;
    }

    public Resource getResource() {
        return resource;
    }

    @Override
    public ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders) {
        if (resource instanceof FileResource file) {
            return executeFile(file, operation, args);
        } else if (resource instanceof QueueResource queue) {
            return executeQueue(queue, operation, args);
        } else if (resource instanceof EmailResource email) {
            return executeEmail(email, operation, args);
        } else if (resource instanceof DatabaseResource db) {
            return executeDatabase(db, operation, args);
        } else if (resource instanceof HttpResource http) {
            return executeHttp(http, operation, args, customHeaders);
        }
        throw new RuntimeException("Unsupported resource type: " + (resource != null ? resource.getClass().getName() : "null"));
    }

    private ResponseObject executeFile(FileResource file, String operation, List<Object> args) {
        long start = System.currentTimeMillis();
        String op = operation.toLowerCase(Locale.ROOT);
        Object body = switch (op) {
            case "get" -> file.get(argString(args, 0));
            case "put" -> file.put(argString(args, 0), args.size() > 1 ? args.get(1) : "");
            case "list" -> file.list(argString(args, 0));
            case "delete" -> file.delete(argString(args, 0));
            default -> throw new RuntimeException(
                    "file resource '" + definition.name() + "' does not support operation '" + operation + "'");
        };
        int status = "get".equals(op) && isMissing(body) ? 404 : 200;
        return new ResponseObject(status, Map.of("Content-Type", "application/json"), body,
                System.currentTimeMillis() - start);
    }

    private boolean isMissing(Object body) {
        return body instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("exists"));
    }

    private ResponseObject executeQueue(QueueResource queue, String operation, List<Object> args) {
        long start = System.currentTimeMillis();
        String op = operation.toLowerCase(Locale.ROOT);
        Map<String, String> headers = Map.of("Content-Type", "application/json");
        return switch (op) {
            case "send" -> {
                Object payload = args.isEmpty() ? null : args.get(0);
                Map<String, Object> body = queue.send(payload);
                yield new ResponseObject(200, headers, body, System.currentTimeMillis() - start);
            }
            case "receive" -> {
                Object item = queue.receive();
                int status = item != null ? 200 : 204;
                yield new ResponseObject(status, headers, item, System.currentTimeMillis() - start);
            }
            case "peek" -> {
                Object item = queue.peek();
                int status = item != null ? 200 : 204;
                yield new ResponseObject(status, headers, item, System.currentTimeMillis() - start);
            }
            default -> throw new RuntimeException(
                    "queue resource '" + definition.name() + "' does not support operation '" + operation + "'");
        };
    }

    private ResponseObject executeEmail(EmailResource email, String operation, List<Object> args) {
        long start = System.currentTimeMillis();
        String op = operation.toLowerCase(Locale.ROOT);
        Map<String, String> headers = Map.of("Content-Type", "application/json");
        return switch (op) {
            case "send" -> {
                Object value = args.isEmpty() ? null : args.get(0);
                if (!(value instanceof Map<?, ?> message)) {
                    throw new RuntimeException("email resource: send expects a message object");
                }
                String to = recipients(message.get("to"));
                String subject = String.valueOf(message.get("subject"));
                String from = message.get("from") != null
                        ? String.valueOf(message.get("from"))
                        : (definition.from() != null ? definition.from() : "_no-reply@bbrun");
                String body = message.get("body") != null ? String.valueOf(message.get("body")) : "";
                if (subject == null || "null".equals(subject)) {
                    throw new RuntimeException("email resource: message requires a 'subject'");
                }
                if (to == null || to.isBlank()) {
                    throw new RuntimeException("email resource: message requires a 'to' recipient");
                }

                email.send(Email.of(from, to, subject, body));

                String msgId = "msg_" + emailIdCounter.incrementAndGet();
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("accepted", true);
                accepted.put("messageId", msgId);
                accepted.put("from", from);
                accepted.put("to", to);
                accepted.put("subject", subject);
                yield new ResponseObject(200, headers, accepted, System.currentTimeMillis() - start);
            }
            case "outbox" -> {
                List<Map<String, Object>> outboxList = new ArrayList<>();
                Optional<CapturedEmails> captured = email.capability(CapturedEmails.class);
                if (captured.isEmpty() && email instanceof CapturedEmails cap) {
                    captured = Optional.of(cap);
                }
                if (captured.isPresent()) {
                    int counter = 1;
                    for (Email msg : captured.get().messages()) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("id", "msg_" + (counter++));
                        record.put("from", msg.from());
                        record.put("to", String.join(", ", msg.to()));
                        record.put("subject", msg.subject());
                        record.put("body", msg.body());
                        record.put("timestampMs", System.currentTimeMillis());
                        outboxList.add(record);
                    }
                }
                yield new ResponseObject(200, headers, outboxList, System.currentTimeMillis() - start);
            }
            default -> throw new RuntimeException(
                    "email resource '" + definition.name() + "' does not support operation '" + operation + "'");
        };
    }

    private ResponseObject executeDatabase(DatabaseResource db, String operation, List<Object> args) {
        long start = System.currentTimeMillis();
        String op = operation.toLowerCase(Locale.ROOT);
        if (!"query".equals(op) && !"execute".equals(op)) {
            throw new RuntimeException("sql resource '" + definition.name() + "' does not support operation '" + operation + "'");
        }
        String sql = args.isEmpty() || args.get(0) == null
                ? null
                : String.valueOf(args.get(0));
        if (sql == null || sql.isBlank()) {
            throw new RuntimeException("sql resource: '" + operation + "' expects a SQL string");
        }

        String trimmed = sql.trim();
        String firstWord = trimmed.split("\\s+")[0].toUpperCase(Locale.ROOT);
        boolean isSelect = "SELECT".equals(firstWord);

        if ("query".equals(op) && !isSelect) {
            throw new RuntimeException("sql resource: 'query' expects a SELECT statement");
        }
        if ("execute".equals(op) && isSelect) {
            throw new RuntimeException("sql resource: 'execute' expects INSERT/UPDATE/DELETE/CREATE, got SELECT");
        }

        try (Connection conn = db.connection();
             Statement stmt = conn.createStatement()) {
            if (isSelect) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String label = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            row.put(label, val);
                            if (!label.toLowerCase(Locale.ROOT).equals(label)) {
                                row.put(label.toLowerCase(Locale.ROOT), val);
                            }
                        }
                        rows.add(row);
                    }
                    return new ResponseObject(200, Map.of("Content-Type", "application/json"), rows,
                            System.currentTimeMillis() - start);
                }
            } else {
                int affected = stmt.executeUpdate(sql);
                Map<String, Object> res = Map.of("affectedRows", affected);
                return new ResponseObject(200, Map.of("Content-Type", "application/json"), res,
                        System.currentTimeMillis() - start);
            }
        } catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.toLowerCase(Locale.ROOT).contains("not found")) {
                throw new RuntimeException("sql resource: unknown table in query: " + msg, e);
            }
            throw new RuntimeException("sql resource error: " + msg, e);
        }
    }

    private ResponseObject executeHttp(HttpResource http, String operation, List<Object> args, Map<String, String> customHeaders) {
        String normalizedVerb = operation.toUpperCase(Locale.ROOT);
        String path = argString(args, 0);
        if (path.startsWith("#")) {
            path = path.substring(1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        Object body = args.size() > 1 ? args.get(1) : null;
        String jsonBody = null;
        if (body != null) {
            jsonBody = (body instanceof String s) ? s : JSON.toJSONString(body);
        }

        HttpRequest.Builder builder = HttpRequest.builder()
                .method(normalizedVerb)
                .path(path)
                .body(jsonBody);

        builder.header("Content-Type", "application/json");
        builder.header("Accept", "application/json");

        if (customHeaders != null) {
            builder.headers(customHeaders);
        }

        long start = System.currentTimeMillis();
        HttpResponse resp = http.send(builder.build());
        long duration = System.currentTimeMillis() - start;

        Object parsedBody = parseBody(resp.body());
        return new ResponseObject(resp.statusCode(), resp.headers(), parsedBody, duration);
    }

    private static String argString(List<Object> args, int index) {
        if (args == null || args.size() <= index || args.get(index) == null) {
            throw new RuntimeException("operation expects a path string as its first argument");
        }
        Object value = args.get(index);
        if (!(value instanceof String s)) {
            throw new RuntimeException("operation expects a path string, got: " + value.getClass().getSimpleName());
        }
        return s;
    }

    private String recipients(Object to) {
        if (to == null) {
            return null;
        }
        if (to instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(item);
            }
            return sb.toString();
        }
        return String.valueOf(to);
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.parse(body);
        } catch (Exception e) {
            return body;
        }
    }
}
