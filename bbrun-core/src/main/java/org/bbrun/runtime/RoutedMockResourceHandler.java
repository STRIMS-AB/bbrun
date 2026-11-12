package org.bbrun.runtime;

import org.bbrun.ast.Statements.ScriptNode;
import org.bbrun.config.MockRoute;
import org.bbrun.config.ResourceDefinition;
import org.bbrun.interpreter.ExecutionContext;
import org.bbrun.interpreter.Interpreter;
import org.bbrun.parser.ScriptParser;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.DrefValidatorBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mock of a non-HTTP resource ({@code mock: sql|queue|email|file} in the
 * config). Route selection is by dispatched operation ({@code op}) plus an
 * optional {@code when} condition, so a mock can answer differently per test
 * case. Exactly as with HTTP mocks, each route produces its body from one
 * source ({@code data}, {@code file}, {@code expr} or {@code script}); a
 * script route runs the full scripting language with {@code op} and
 * {@code args} bound, and may use {@code return <expr>} or
 * {@code respond(status, body)}.
 *
 * <p>Unlike HTTP mocks there is no lifecycle fallback: an operation that
 * matches no route fails with a clear error. Adding a new mockable kind is a
 * matter of extending {@link #OPERATIONS}.
 */
public class RoutedMockResourceHandler implements ResourceHandler, ResourceContextAware {

    /** Vocabulary each mockable non-HTTP resource kind understands. */
    public static final Map<String, Set<String>> OPERATIONS = Map.of(
            "sql", Set.of("query", "execute"),
            "queue", Set.of("send", "receive", "peek"),
            "email", Set.of("send", "outbox"),
            "file", Set.of("get", "put", "list", "delete")
    );

    private static final long DURATION = 5L;

    private final ResourceDefinition definition;
    private ResourceContext resourceContext;

    public RoutedMockResourceHandler(ResourceDefinition definition) {
        this.definition = definition;
        for (MockRoute route : definition.routes()) {
            if (route.op() == null) {
                throw new IllegalArgumentException(
                        "Mock of kind '" + definition.mockKind() + "' needs routes with 'op'");
            }
        }
    }

    @Override
    public void attachResourceContext(ResourceContext context) {
        this.resourceContext = context;
    }

    @Override
    public ResourceDefinition getDefinition() {
        return definition;
    }

    @Override
    public ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders) {
        String kind = definition.mockKind();
        Set<String> allowed = OPERATIONS.get(kind);
        if (allowed != null && !allowed.contains(operation.toLowerCase(Locale.ROOT))) {
            throw new RuntimeException("Unknown operation '" + operation + "' for mock of kind '" + kind +
                    "'. Expected one of: " + allowed);
        }

        for (MockRoute route : definition.routes()) {
            if (route.op() == null || !route.op().equalsIgnoreCase(operation)) {
                continue;
            }
            if (route.when() != null && !truthy(evaluateExpr(route.when(), operation, args, customHeaders))) {
                continue;
            }
            Object data = null;
            int status = route.status() != null ? route.status() : 200;
            if (route.expr() != null) {
                data = evaluateExpr(route.expr(), operation, args, customHeaders);
            } else if (route.script() != null) {
                MockResult result = runScript(route.script(), operation, args, customHeaders);
                status = result.status();
                data = result.body();
            } else if (route.file() != null) {
                data = loadFile(route.file());
            } else if (route.data() != null) {
                data = route.data();
            }
            return new ResponseObject(status, Map.of("Content-Type", "application/json"), data, DURATION);
        }

        throw new RuntimeException(
                "Mock of kind '" + kind + "' has no route for operation '" + operation + "': mocks answer only from configured routes");
    }

    private record MockResult(int status, Object body) {}

    private Object evaluateExpr(String expr, String op, List<Object> args, Map<String, String> headers) {
        ExecutionContext ctx = newContext(op, args, headers);
        ScriptNode parsed = ScriptParser.parse("let __mock_body__ = (" + expr + ")");
        Interpreter interpreter = new Interpreter(ctx);
        for (org.bbrun.ast.StatementNode stmt : parsed.statements()) {
            interpreter.executeStatement(stmt);
        }
        return ctx.getVariable("__mock_body__");
    }

    private MockResult runScript(String scriptPath, String op, List<Object> args, Map<String, String> headers) {
        ExecutionContext ctx = newContext(op, args, headers);
        Interpreter interpreter = new Interpreter(ctx);
        try {
            ScriptNode parsed = ScriptParser.parse(java.nio.file.Path.of(scriptPath));
            for (org.bbrun.ast.StatementNode stmt : parsed.statements()) {
                interpreter.executeStatement(stmt);
            }
        } catch (Interpreter.MockReturnSignal ignored) {
        } catch (java.io.IOException e) {
            throw new RuntimeException("Mock script '" + scriptPath + "' could not be read: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Mock script '" + scriptPath + "' failed for op '" + op + "': " + e.getMessage(), e);
        }
        MockResponseSink sink = ctx.getMockSink();
        if (sink.isResponded()) {
            return new MockResult(sink.getStatus(), sink.getBody());
        }
        return new MockResult(200, null);
    }

    /**
     * The mock bindings: {@code op}, {@code args}, {@code headers} — plus
     * {@code body} as the last positional argument (the payload in the
     * common shapes: {@code send(payload)}, {@code put(path, payload)},
     * {@code execute(statement)}).
     */
    private ExecutionContext newContext(String op, List<Object> args, Map<String, String> headers) {
        List<Object> safeArgs = args != null ? List.copyOf(args) : List.of();
        Object body = !safeArgs.isEmpty() ? safeArgs.get(safeArgs.size() - 1) : null;
        ResourceContext resources = resourceContext != null ? resourceContext : new ResourceContext(null);
        ExecutionContext ctx = new ExecutionContext(resources, new DrefValidatorBridge(new DrefRegistry()));
        ctx.setMockSink(new MockResponseSink());
        ctx.setVariable("op", op);
        ctx.setVariable("args", safeArgs);
        ctx.setVariable("body", body);
        ctx.setVariable("params", Map.of());
        ctx.setVariable("query", Map.of());
        ctx.setVariable("headers", headers != null ? Map.copyOf(headers) : Map.of());
        return ctx;
    }

    private boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    @SuppressWarnings("unchecked")
    private Object loadFile(String filePath) {
        try {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
            Object parsed = com.alibaba.fastjson2.JSON.parse(content);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return parsed;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Mock route: cannot read fixture file '" + filePath + "': " + e.getMessage(), e);
        }
    }
}
