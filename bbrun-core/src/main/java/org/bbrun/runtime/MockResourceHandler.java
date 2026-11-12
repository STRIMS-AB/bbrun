package org.bbrun.runtime;

import com.alibaba.fastjson2.JSON;
import org.bbrun.config.MockRoute;
import org.bbrun.config.ResourceDefinition;
import org.bbrun.interpreter.ExecutionContext;
import org.bbrun.interpreter.Interpreter;
import org.bbrun.ast.Statements.ScriptNode;
import org.bbrun.parser.ScriptParser;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.DrefValidatorBridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory mock resource handler.
 *
 * <p>The mock serves two sources of truth. First, the configured {@code routes}
 * (see {@link MockRoute}): the response for a matching {@code verb + path} is
 * produced from literal {@code data}, a JSON {@code file}, a BBRun {@code expr},
 * or a full BBRun {@code script} handler. Script routes run the scripting
 * language WITHOUT the project's domain models - the mock simulates the other
 * side of the wire and must not rely on our models. Second, the generic
 * lifecycle fallback: {@code post} appends to a per-path collection, {@code get}
 * on a collection returns the array, and indexed {@code get}/{@code put}/
 * {@code patch}/{@code delete} read, replace, change the given fields of, and
 * remove a single stored object, in the collection's array too.
 */
public class MockResourceHandler implements ResourceHandler, ResourceContextAware {

    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final ResourceDefinition definition;
    private final Map<String, Object> mockStore = new HashMap<>();
    private final Map<String, List<Object>> collections = new HashMap<>();
    private final List<RouteMatcher> routeMatchers = new ArrayList<>();

    private ResourceContext resourceContext;

    public MockResourceHandler(ResourceDefinition definition) {
        this.definition = definition;
        for (MockRoute route : definition.routes()) {
            routeMatchers.add(RouteMatcher.compile(route));
        }
    }

    /**
     * Attaches the enclosing resource context so that script routes can chain
     * calls to other configured resources.
     */
    public void attachResourceContext(ResourceContext context) {
        this.resourceContext = context;
    }

    @Override
    public ResourceDefinition getDefinition() {
        return definition;
    }

    @Override
    public ResponseObject execute(String operation, List<Object> args, Map<String, String> customHeaders) {
        String normalizedVerb = operation.toLowerCase(Locale.ROOT);
        String path = argString(args, 0);
        Object body = args.size() > 1 ? args.get(1) : null;
        long duration = 5L;

        RouteMatch match = findRoute(normalizedVerb, path, body, customHeaders);
        if (match != null) {
            return produceFromRoute(match, normalizedVerb, path, body, customHeaders, duration);
        }

        return lifecycleFallback(normalizedVerb, path, body, duration);
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

    // ---------------------------------------------------------------
    // Configured routes
    // ---------------------------------------------------------------

    private RouteMatch findRoute(String verb, String path, Object body, Map<String, String> customHeaders) {
        String cleanPath = path;
        Map<String, String> query = new LinkedHashMap<>();
        int queryIdx = path != null ? path.indexOf('?') : -1;
        if (queryIdx >= 0) {
            cleanPath = path.substring(0, queryIdx);
            parseQuery(path.substring(queryIdx + 1), query);
        }
        Map<String, String> headers = customHeaders != null ? Map.copyOf(customHeaders) : Map.of();
        for (RouteMatcher matcher : routeMatchers) {
            if (!matcher.route().matchesVerb(verb)) {
                continue;
            }
            MockRoute route = matcher.route();
            // The path first: a route's `when` reads the same names as its `expr`, `params` among
            // them, and is only asked about a request its path matches
            Map<String, String> params = new LinkedHashMap<>();
            if (matcher.pattern() != null) {
                Matcher m = matcher.pattern().matcher(cleanPath);
                if (!m.matches()) {
                    continue;
                }
                for (int i = 0; i < matcher.paramNames().size(); i++) {
                    params.put(matcher.paramNames().get(i), m.group(i + 1));
                }
            }
            if (route.when() != null && !isTruthy(
                    evaluateExpression(route.when(), verb, path, body, params, query, headers))) {
                continue;
            }
            return new RouteMatch(matcher.route(), params, query);
        }
        return null;
    }

    private boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    private void parseQuery(String queryString, Map<String, String> query) {
        if (queryString == null || queryString.isBlank()) {
            return;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            } else {
                query.put(decode(pair), "");
            }
        }
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private ResponseObject produceFromRoute(
            RouteMatch match, String verb, String path, Object body,
            Map<String, String> customHeaders, long duration) {
        MockRoute route = match.route();
        int status = route.status() != null ? route.status() : 200;
        Object data = null;

        if (route.expr() != null) {
            data = evaluateExpression(route.expr(), verb, path, body, match.params(), match.query(), customHeaders);
        } else if (route.script() != null) {
            MockResult scriptResult = runScript(route.script(), verb, path, body, match, customHeaders);
            status = scriptResult.status();
            data = scriptResult.body();
        } else if (route.file() != null) {
            data = loadFixture(route.file());
        } else if (route.data() != null) {
            data = route.data();
        }

        return new ResponseObject(status, responseHeaders(route, customHeaders), data, duration);
    }

    private Map<String, String> responseHeaders(MockRoute route, Map<String, String> customHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", DEFAULT_CONTENT_TYPE);
        if (route != null) {
            for (Map.Entry<String, Object> h : route.headers().entrySet()) {
                headers.put(h.getKey(), String.valueOf(h.getValue()));
            }
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Object loadFixture(String filePath) {
        try {
            String content = Files.readString(Path.of(filePath));
            Object parsed = JSON.parse(content);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return parsed;
        } catch (IOException e) {
            throw new RuntimeException("Mock route: cannot read fixture file '" + filePath + "': " + e.getMessage(), e);
        }
    }

    private Object evaluateExpression(String expr, String verb, String path, Object body,
                                      Map<String, String> params, Map<String, String> query,
                                      Map<String, String> headers) {
        ExecutionContext ctx = newMockContext(verb, path, body, params, query, headers);
        try {
            ScriptNode parsed = ScriptParser.parse("let __mock_body__ = (" + expr + ")");
            Interpreter interpreter = new Interpreter(ctx);
            for (org.bbrun.ast.StatementNode stmt : parsed.statements()) {
                interpreter.executeStatement(stmt);
            }
            return ctx.getVariable("__mock_body__");
        } catch (RuntimeException e) {
            throw new RuntimeException("Mock route expr for '" + verb + " " + path + "' failed: " + e.getMessage(), e);
        }
    }

    private MockResult runScript(String scriptPath, String verb, String path, Object body, RouteMatch match,
                                 Map<String, String> headers) {
        ExecutionContext ctx = newMockContext(verb, path, body, match.params(), match.query(), headers);
        try {
            ScriptNode parsed = ScriptParser.parse(Path.of(scriptPath));
            Interpreter interpreter = new Interpreter(ctx);
            for (org.bbrun.ast.StatementNode stmt : parsed.statements()) {
                interpreter.executeStatement(stmt);
            }
        } catch (Interpreter.MockReturnSignal s) {
            // `return <expr>` already wrote the response body into the sink
        } catch (RuntimeException e) {
            throw new RuntimeException("Mock script '" + scriptPath + "' failed for '" + verb + " " + path + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Mock script '" + scriptPath + "' could not be read: " + e.getMessage(), e);
        }
        MockResponseSink sink = ctx.getMockSink();
        if (sink.isResponded()) {
            return new MockResult(sink.getStatus(), sink.getBody());
        }
        return new MockResult(200, null);
    }

    private ExecutionContext newMockContext(String verb, String path, Object body,
                                            Map<String, String> params, Map<String, String> query,
                                            Map<String, String> headers) {
        ResourceContext resources = resourceContext != null ? resourceContext : new ResourceContext(null);
        ExecutionContext ctx = new ExecutionContext(resources, new DrefValidatorBridge(new DrefRegistry()));
        ctx.setMockSink(new MockResponseSink());
        ctx.setVariable("verb", verb);
        ctx.setVariable("path", path);
        ctx.setVariable("body", body);
        ctx.setVariable("params", params);
        ctx.setVariable("query", query);
        ctx.setVariable("headers", headers);
        return ctx;
    }

    // ---------------------------------------------------------------
    // Generic lifecycle fallback (unmatched requests)
    // ---------------------------------------------------------------

    private ResponseObject lifecycleFallback(String normalizedVerb, String path, Object body, long duration) {
        if ("get".equals(normalizedVerb)) {
            List<Object> collection = collections.get(path);
            if (collection != null) {
                return new ResponseObject(200, responseHeaders(null, null), List.copyOf(collection), duration);
            }
            Object data = mockStore.getOrDefault(path, Map.of("status", "mocked", "path", path));
            return new ResponseObject(200, responseHeaders(null, null), data, duration);
        } else if ("post".equals(normalizedVerb)) {
            collections.computeIfAbsent(path, k -> new ArrayList<>()).add(body);
            // Index the created entity by id so it can be read/updated/deleted by path
            if (body instanceof Map<?, ?> m && m.containsKey("id")) {
                mockStore.put(path + "/" + m.get("id"), body);
            }
            Map<String, Object> responseBody = new HashMap<>();
            if (body instanceof Map<?, ?> m) {
                m.forEach((k, v) -> responseBody.put(String.valueOf(k), v));
            }
            responseBody.putIfAbsent("id", "mock_" + UUID.randomUUID().toString().substring(0, 8));
            return new ResponseObject(201, responseHeaders(null, null), responseBody, duration);
        } else if ("put".equals(normalizedVerb)) {
            Object stored = mockStore.put(path, body);
            updateCollection(path, stored, body);
            return new ResponseObject(200, responseHeaders(null, null), body, duration);
        } else if ("patch".equals(normalizedVerb)) {
            Object stored = mockStore.get(path);
            if (stored instanceof Map<?, ?> object && body instanceof Map<?, ?> changes) {
                Map<String, Object> patched = new LinkedHashMap<>();
                object.forEach((k, v) -> patched.put(String.valueOf(k), v));
                changes.forEach((k, v) -> patched.put(String.valueOf(k), v));
                mockStore.put(path, patched);
                updateCollection(path, stored, patched);
                return new ResponseObject(200, responseHeaders(null, null), patched, duration);
            }
            return new ResponseObject(200, responseHeaders(null, null), body, duration);
        } else if ("delete".equals(normalizedVerb)) {
            Object stored = mockStore.remove(path);
            updateCollection(path, stored, null);
            return new ResponseObject(204, Map.of(), null, duration);
        } else {
            return new ResponseObject(200, responseHeaders(null, null), body, duration);
        }
    }

    /**
     * Keeps the list of a collection ({@code /payments}) the same as its objects
     * ({@code /payments/pi_1}): the object at {@code path} was {@code stored} and is now
     * {@code now}, or is gone when {@code now} is null. An object put under a collection
     * for the first time joins its list. A path under no posted collection has no list.
     */
    private void updateCollection(String path, Object stored, Object now) {
        int slash = path.lastIndexOf('/');
        List<Object> collection = slash > 0 ? collections.get(path.substring(0, slash)) : null;
        if (collection == null) {
            return;
        }
        int at = -1;
        for (int i = 0; i < collection.size() && stored != null; i++) {
            if (collection.get(i) == stored) {
                at = i;
                break;
            }
        }
        if (now == null) {
            if (at >= 0) collection.remove(at);
        } else if (at >= 0) {
            collection.set(at, now);
        } else {
            collection.add(now);
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private record RouteMatch(MockRoute route, Map<String, String> params, Map<String, String> query) {}

    private record MockResult(int status, Object body) {}

    /**
     * A compiled route matcher. The {@code path} pattern may contain
     * {@code {param}} placeholders; each placeholder captures one path segment.
     */
    private record RouteMatcher(MockRoute route, Pattern pattern, List<String> paramNames) {

        static RouteMatcher compile(MockRoute route) {
            String path = route.path();
            if (path == null || path.isBlank()) {
                return new RouteMatcher(route, null, List.of());
            }
            List<String> paramNames = new ArrayList<>();
            StringBuilder regex = new StringBuilder("^");
            String[] segments = path.split("/");
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.isBlank()) {
                    continue;
                }
                if (i > 0) regex.append("\\/");
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    String name = segment.substring(1, segment.length() - 1).trim();
                    paramNames.add(name);
                    regex.append("([^/]+)");
                } else {
                    regex.append(Pattern.quote(segment));
                }
            }
            regex.append("$");
            return new RouteMatcher(route, Pattern.compile(regex.toString()), List.copyOf(paramNames));
        }
    }
}