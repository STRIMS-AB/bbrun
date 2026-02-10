package org.bbrun.interpreter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bbrun.BBRunException;
import org.bbrun.RequestMetric;
import org.bbrun.Warning;
import org.bbrun.ast.*;
import org.bbrun.events.EventListener;
import org.bbrun.spi.HttpClient;
import org.bbrun.spi.HttpClient.HttpRequest;
import org.bbrun.spi.HttpClient.HttpResponse;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Executes individual statements within a script.
 */
public class StatementExecutor {

    private final Context context;
    private final HttpClient httpClient;
    private final ExecutionHandle handle;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final List<Warning> warnings = new ArrayList<>();
    private final List<RequestMetric> requests = new ArrayList<>();
    private int passedAssertions = 0;
    private int failedAssertions = 0;

    public StatementExecutor(Context context, HttpClient httpClient, ExecutionHandle handle) {
        this.context = context;
        this.httpClient = httpClient;
        this.handle = handle;
    }

    /**
     * Execute a single statement.
     * 
     * @return false if execution should stop (failed assertion with failFast)
     */
    public boolean execute(StatementNode statement) {
        try {
            if (statement instanceof BaseUrlNode n) {
                return executeBaseUrl(n);
            } else if (statement instanceof VariableNode n) {
                return executeVariable(n);
            } else if (statement instanceof RequestNode n) {
                return executeRequest(n);
            } else if (statement instanceof AssertNode n) {
                return executeAssert(n);
            } else if (statement instanceof WarnNode n) {
                return executeWarn(n);
            } else if (statement instanceof PrintNode n) {
                return executePrint(n);
            } else if (statement instanceof IfNode n) {
                return executeIf(n);
            } else if (statement instanceof RepeatNode n) {
                return executeRepeat(n);
            } else {
                return true; // Unknown statement types are no-ops for now
            }
        } catch (Exception e) {
            throw new BBRunException(e.getMessage(), statement.line(), null, e);
        }
    }

    // ========== Statement Execution ==========

    private boolean executeBaseUrl(BaseUrlNode node) {
        String url = node.url();
        // Handle env() function in URL
        if (url.startsWith("env(")) {
            url = evaluateEnvCall(url);
        }
        context.setBaseUrl(url);
        return true;
    }

    private boolean executeVariable(VariableNode node) {
        Object value = evaluate(node.value());
        context.setVariable(node.name(), value);
        return true;
    }

    private boolean executeRequest(RequestNode node) {
        String method = node.method();
        String url = buildUrl(node.path());

        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        // Apply auth
        applyAuth(headers, node.authClause());

        // Build body
        byte[] body = null;
        if (node.body() != null) {
            Object bodyValue = evaluate(node.body());
            body = gson.toJson(bodyValue).getBytes();
        }

        // Notify request start
        if (handle != null) {
            for (EventListener listener : handle.getListeners()) {
                listener.onRequestStart(new EventListener.RequestEvent(
                        method, url, 0, 0, false, node.line()));
            }
        }

        // Execute request
        HttpRequest request = new HttpRequest(method, url, headers, body);
        HttpResponse response = httpClient.execute(request);

        // Store response in context
        ResponseObject responseObj = new ResponseObject(
                response.status(),
                response.headers(),
                parseJson(response.bodyAsString()),
                response.durationMs());
        context.setVariable("response", responseObj);

        // Track request metric
        boolean success = response.isSuccess();
        RequestMetric metric = new RequestMetric(method, url, response.status(), response.durationMs(), success);
        requests.add(metric);

        // Notify request complete
        if (handle != null) {
            for (EventListener listener : handle.getListeners()) {
                listener.onRequestComplete(new EventListener.RequestEvent(
                        method, url, response.status(), response.durationMs(), success, node.line()));
            }
        }

        // Auto-fail on non-2xx (unless inside expect block)
        if (!success && context.getOptions().isFailFast()) {
            throw new BBRunException("Request failed with status " + response.status(), node.line(), null);
        }

        return true;
    }

    private boolean executeAssert(AssertNode node) {
        Object result = evaluate(node.condition());
        boolean passed = isTruthy(result);

        if (passed) {
            passedAssertions++;
            if (handle != null) {
                for (EventListener listener : handle.getListeners()) {
                    listener.onAssertionPass(new EventListener.AssertionEvent(
                            node.condition().toString(), true, node.message(), node.line()));
                }
            }
        } else {
            failedAssertions++;
            String message = node.message() != null ? node.message() : "Assertion failed";
            if (handle != null) {
                for (EventListener listener : handle.getListeners()) {
                    listener.onAssertionFail(new EventListener.AssertionEvent(
                            node.condition().toString(), false, message, node.line()));
                }
            }
            if (context.getOptions().isFailFast()) {
                throw new BBRunException(message, node.line(), null);
            }
        }

        return passed || !context.getOptions().isFailFast();
    }

    private boolean executeWarn(WarnNode node) {
        Object result = evaluate(node.condition());
        boolean passed = isTruthy(result);

        if (!passed) {
            String message = node.message() != null ? node.message() : "Warning condition failed";
            Warning warning = new Warning(message, node.line(), node.condition().toString());
            warnings.add(warning);
            if (handle != null) {
                for (EventListener listener : handle.getListeners()) {
                    listener.onWarning(new EventListener.WarningEvent(warning));
                }
            }
        }

        return true; // warnings never stop execution
    }

    private boolean executePrint(PrintNode node) {
        Object value = evaluate(node.message());
        String output = (value instanceof String) ? (String) value : gson.toJson(value);
        System.out.println(output);
        return true;
    }

    private boolean executeIf(IfNode node) {
        Object condResult = evaluate(node.condition());

        if (isTruthy(condResult)) {
            for (StatementNode stmt : node.thenBlock()) {
                if (!execute(stmt))
                    return false;
            }
            return true;
        }

        // Check else-if clauses
        for (IfNode.ElseIfClause clause : node.elseIfClauses()) {
            Object elseIfResult = evaluate(clause.condition());
            if (isTruthy(elseIfResult)) {
                for (StatementNode stmt : clause.block()) {
                    if (!execute(stmt))
                        return false;
                }
                return true;
            }
        }

        // Else block
        if (node.elseBlock() != null) {
            for (StatementNode stmt : node.elseBlock()) {
                if (!execute(stmt))
                    return false;
            }
        }

        return true;
    }

    private boolean executeRepeat(RepeatNode node) {
        Object countValue = evaluate(node.count());
        int count = ((Number) countValue).intValue();

        for (int i = 0; i < count; i++) {
            context.setVariable("iteration", i);
            for (StatementNode stmt : node.body()) {
                if (!execute(stmt))
                    return false;
            }
        }

        return true;
    }

    // ========== Expression Evaluation ==========

    public Object evaluate(ExpressionNode expr) {
        if (expr instanceof LiteralNode n) {
            return n.value();
        } else if (expr instanceof IdentifierNode n) {
            return context.getVariable(n.name());
        } else if (expr instanceof MemberAccessNode n) {
            return evaluateMemberAccess(n);
        } else if (expr instanceof IndexAccessNode n) {
            return evaluateIndexAccess(n);
        } else if (expr instanceof FunctionCallNode n) {
            return evaluateFunctionCall(n);
        } else if (expr instanceof BinaryOpNode n) {
            return evaluateBinaryOp(n);
        } else if (expr instanceof UnaryOpNode n) {
            return evaluateUnaryOp(n);
        } else if (expr instanceof IsCheckNode n) {
            return evaluateIsCheck(n);
        } else if (expr instanceof ContainsNode n) {
            return evaluateContains(n);
        } else if (expr instanceof ObjectLiteralNode n) {
            return evaluateObjectLiteral(n);
        } else if (expr instanceof ArrayLiteralNode n) {
            return evaluateArrayLiteral(n);
        } else {
            return null;
        }
    }

    private Object evaluateMemberAccess(MemberAccessNode node) {
        Object obj = evaluate(node.object());
        String member = node.member();

        if (obj instanceof ResponseObject) {
            ResponseObject resp = (ResponseObject) obj;
            switch (member) {
                case "status":
                    return resp.status();
                case "headers":
                    return resp.headers();
                case "body":
                    return resp.body();
                case "time":
                    return resp.durationMs();
                default:
                    return null;
            }
        }

        if (obj instanceof Map<?, ?>) {
            return ((Map<?, ?>) obj).get(member);
        }

        return null;
    }

    private Object evaluateIndexAccess(IndexAccessNode node) {
        Object obj = evaluate(node.object());
        Object index = evaluate(node.index());

        if (obj instanceof List<?> && index instanceof Number) {
            return ((List<?>) obj).get(((Number) index).intValue());
        }

        if (obj instanceof Map<?, ?>) {
            return ((Map<?, ?>) obj).get(index);
        }

        return null;
    }

    private Object evaluateFunctionCall(FunctionCallNode node) {
        String name = node.name();
        List<ExpressionNode> args = node.arguments();

        switch (name) {
            case "env":
                String varName = (String) evaluate(args.get(0));
                String envValue = System.getenv(varName);
                if (envValue != null) {
                    return envValue;
                }
                // Return default if provided, otherwise null
                return args.size() > 1 ? evaluate(args.get(1)) : null;
            case "load":
                // TODO: Load JSON file
                return null;
            case "uuid":
                return UUID.randomUUID().toString();
            case "now":
                return System.currentTimeMillis();
            case "random":
                if (args.isEmpty()) {
                    return Math.random();
                } else if (args.size() == 1) {
                    // random(max) - returns 0 to max-1
                    int max = ((Number) evaluate(args.get(0))).intValue();
                    return (int) (Math.random() * max);
                } else {
                    // random(min, max) - returns min to max (inclusive)
                    int min = ((Number) evaluate(args.get(0))).intValue();
                    int max = ((Number) evaluate(args.get(1))).intValue();
                    return min + (int) (Math.random() * (max - min + 1));
                }
            case "randomString":
                int length = args.isEmpty() ? 8 : ((Number) evaluate(args.get(0))).intValue();
                return generateRandomString(length);
            default:
                return null;
        }
    }

    private Object evaluateBinaryOp(BinaryOpNode node) {
        Object left = evaluate(node.left());
        Object right = evaluate(node.right());
        String op = node.operator();

        switch (op) {
            case "==":
                return numericEquals(left, right);
            case "!=":
                return !numericEquals(left, right);
            case "<":
                return compare(left, right) < 0;
            case ">":
                return compare(left, right) > 0;
            case "<=":
                return compare(left, right) <= 0;
            case ">=":
                return compare(left, right) >= 0;
            case "+":
                return add(left, right);
            case "-":
                return subtract(left, right);
            case "*":
                return multiply(left, right);
            case "/":
                return divide(left, right);
            case "and":
                return isTruthy(left) && isTruthy(right);
            case "or":
                return isTruthy(left) || isTruthy(right);
            default:
                return null;
        }
    }

    private Object evaluateUnaryOp(UnaryOpNode node) {
        Object operand = evaluate(node.operand());
        switch (node.operator()) {
            case "not":
                return !isTruthy(operand);
            case "-":
                return (operand instanceof Number) ? -((Number) operand).doubleValue() : null;
            default:
                return null;
        }
    }

    private Object evaluateIsCheck(IsCheckNode node) {
        Object value = evaluate(node.expression());
        String type = node.typeOrFormat();

        if (node.isRegex()) {
            if (value == null)
                return false;
            return Pattern.matches(type, value.toString());
        }

        switch (type) {
            case "number":
                return value instanceof Number;
            case "string":
                return value instanceof String;
            case "boolean":
                return value instanceof Boolean;
            case "array":
                return value instanceof List;
            case "object":
                return value instanceof Map;
            case "email":
                return (value instanceof String) && ((String) value).matches("^[^@]+@[^@]+\\.[^@]+$");
            case "url":
                return (value instanceof String) &&
                        (((String) value).startsWith("http://") || ((String) value).startsWith("https://"));
            case "uuid":
                return (value instanceof String) &&
                        ((String) value).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
            default:
                return false;
        }
    }

    private Object evaluateContains(ContainsNode node) {
        Object container = evaluate(node.container());
        Object item = evaluate(node.item());
        boolean result;

        if (container instanceof List<?>) {
            result = ((List<?>) container).contains(item);
        } else if (container instanceof String && item instanceof String) {
            result = ((String) container).contains((String) item);
        } else if (container instanceof Map<?, ?> && item instanceof String) {
            result = ((Map<?, ?>) container).containsKey(item);
        } else {
            result = false;
        }

        return node.negated() ? !result : result;
    }

    private Object evaluateObjectLiteral(ObjectLiteralNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ExpressionNode> entry : node.properties().entrySet()) {
            result.put(entry.getKey(), evaluate(entry.getValue()));
        }
        return result;
    }

    private Object evaluateArrayLiteral(ArrayLiteralNode node) {
        List<Object> result = new ArrayList<>();
        for (ExpressionNode element : node.elements()) {
            result.add(evaluate(element));
        }
        return result;
    }

    /**
     * Compare two values for equality, handling numeric type differences.
     */
    private boolean numericEquals(Object left, Object right) {
        if (left == null && right == null)
            return true;
        if (left == null || right == null)
            return false;

        // Handle numeric comparisons - Integer vs Long etc.
        if (left instanceof Number && right instanceof Number) {
            // Compare as double to handle int/long/double equality
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }

        return Objects.equals(left, right);
    }

    // ========== Helpers ==========

    private String buildUrl(PathNode path) {
        StringBuilder sb = new StringBuilder();

        for (PathNode.PathSegment segment : path.segments()) {
            if (segment instanceof PathNode.LiteralSegment) {
                PathNode.LiteralSegment lit = (PathNode.LiteralSegment) segment;
                String value = lit.value();
                // Check if it's a full URL
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    sb.append(value);
                    continue;
                }
                if (sb.length() > 0 || value.startsWith("/")) {
                    sb.append("/");
                }
                sb.append(value);
            } else if (segment instanceof PathNode.InterpolatedSegment) {
                PathNode.InterpolatedSegment interp = (PathNode.InterpolatedSegment) segment;
                Object value = evaluate(interp.expression());
                sb.append("/").append(value);
            }
        }

        String pathStr = sb.toString();

        // Resolve against baseUrl if relative
        if (!pathStr.startsWith("http://") && !pathStr.startsWith("https://")) {
            pathStr = context.resolveUrl(pathStr);
        }

        // Add query params
        if (!path.queryParams().isEmpty()) {
            StringBuilder query = new StringBuilder("?");
            boolean first = true;
            for (Map.Entry<String, ExpressionNode> entry : path.queryParams().entrySet()) {
                if (!first)
                    query.append("&");
                first = false;
                query.append(entry.getKey()).append("=").append(evaluate(entry.getValue()));
            }
            pathStr += query;
        }

        return pathStr;
    }

    private void applyAuth(Map<String, String> headers, AuthClauseNode authClause) {
        Context.AuthState auth = null;

        if (authClause == null) {
            auth = context.getAuth();
        } else if (authClause instanceof WithoutAuth) {
            return; // explicitly no auth
        } else if (authClause instanceof UsingClause) {
            auth = context.getNamedAuth(((UsingClause) authClause).name());
        } else if (authClause instanceof BearerClause) {
            String token = (String) evaluate(((BearerClause) authClause).token());
            auth = new Context.BearerAuth(token);
        } else if (authClause instanceof BasicClause) {
            BasicClause basic = (BasicClause) authClause;
            String user = (String) evaluate(basic.username());
            String pass = (String) evaluate(basic.password());
            auth = new Context.BasicAuth(user, pass);
        }

        if (auth != null) {
            auth.apply(headers);
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            JsonElement element = JsonParser.parseString(json);
            return gson.fromJson(element, Object.class);
        } catch (Exception e) {
            return json; // return as string if not valid JSON
        }
    }

    private String evaluateEnvCall(String envCall) {
        int start = envCall.indexOf("\"") + 1;
        int end = envCall.indexOf("\"", start);
        String varName = envCall.substring(start, end);
        return context.getEnv(varName, "");
    }

    private boolean isTruthy(Object value) {
        if (value == null)
            return false;
        if (value instanceof Boolean)
            return (Boolean) value;
        if (value instanceof Number)
            return ((Number) value).doubleValue() != 0;
        if (value instanceof String)
            return !((String) value).isEmpty();
        if (value instanceof Collection<?>)
            return !((Collection<?>) value).isEmpty();
        return true;
    }

    @SuppressWarnings("unchecked")
    private int compare(Object left, Object right) {
        if (left instanceof Comparable && right instanceof Comparable) {
            return ((Comparable<Object>) left).compareTo(right);
        }
        return 0;
    }

    private Object add(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() + ((Number) right).doubleValue();
        }
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left) + String.valueOf(right);
        }
        return null;
    }

    private Object subtract(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() - ((Number) right).doubleValue();
        }
        return null;
    }

    private Object multiply(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() * ((Number) right).doubleValue();
        }
        return null;
    }

    private Object divide(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() / ((Number) right).doubleValue();
        }
        return null;
    }

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final java.util.Random RANDOM = new java.util.Random();

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    // ========== Results ==========

    public List<Warning> getWarnings() {
        return warnings;
    }

    public List<RequestMetric> getRequests() {
        return requests;
    }

    public int getPassedAssertions() {
        return passedAssertions;
    }

    public int getFailedAssertions() {
        return failedAssertions;
    }

    // Response object wrapper
    public record ResponseObject(
            int status,
            Map<String, String> headers,
            Object body,
            long durationMs) {
    }

}
