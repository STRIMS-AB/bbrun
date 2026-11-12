package org.bbrun.interpreter;

import com.alibaba.fastjson2.JSON;
import org.bbrun.ast.ExpressionNode;
import org.bbrun.ast.Expressions.*;
import org.bbrun.ast.Node;
import org.bbrun.ast.StatementNode;
import org.bbrun.ast.Statements;
import org.bbrun.ast.Statements.*;
import org.bbrun.runtime.MockResponseSink;
import org.bbrun.runtime.ResponseObject;
import org.bbrun.validation.DrefValidationException;
import dev.dref.DomainModel;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Interpreter {

    private static final Pattern INTERPOLATION_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final class BreakSignal extends RuntimeException {}

    /** Signals that a mock handler script produced its response via `return`. */
    public static final class MockReturnSignal extends RuntimeException {}

    /** Signals that a script produced its flow value via `return <expr>`. */
    public static final class TopLevelReturnSignal extends RuntimeException {
        private final Object value;
        TopLevelReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
        public Object value() { return value; }
    }
    private final ExecutionContext context;
    private final SecureRandom random = new SecureRandom();

    public Interpreter(ExecutionContext context) {
        this.context = context;
    }

    public ExecutionContext getContext() {
        return context;
    }

    public ExecutionResult execute(ScriptNode script) {
        boolean returned = false;
        Object value = null;
        String error = null;
        String reason = null;
        try {
            for (StatementNode stmt : script.statements()) {
                executeStatement(stmt);
            }
        } catch (BreakSignal b) {
            // a break outside a loop ends the flow
        } catch (TopLevelReturnSignal r) {
            returned = true;
            value = r.value();
        } catch (Exception | AssertionError e) {
            error = e.getMessage();
            reason = reasonOf(e);
        }
        String cleanupError = runCleanups(error != null);
        if (error == null && cleanupError != null) {
            error = cleanupError;
            reason = cleanupError;
        }
        ExecutionResult result;
        if (error != null) {
            result = ExecutionResult.failure(context.getDurationMs(), context.getPassedAssertions(),
                    context.getFailedAssertions(), context.getWarnings(), context.getRequestCount(), error);
        } else if (returned) {
            result = ExecutionResult.successWithOutcome(context.getDurationMs(), context.getPassedAssertions(),
                    context.getFailedAssertions(), context.getWarnings(), context.getRequestCount(), value,
                    context.getStatusCode(),
                    context.getResponseHeaders().isEmpty() ? null : context.getResponseHeaders());
        } else {
            result = ExecutionResult.success(context.getDurationMs(), context.getPassedAssertions(),
                    context.getFailedAssertions(), context.getWarnings(), context.getRequestCount());
        }
        if (result.isSuccess()) {
            context.getOutput().line("DONE", "passed in " + result.durationMs() + " ms · "
                    + plural(result.passedAssertions(), "assertion") + " · " + plural(result.requestCount(), "request"));
        } else {
            context.getOutput().line("ERROR", result.errorMessage());
        }
        return result.withScenario(context.getGoals(), context.getSteps()).withReason(reason);
    }

    /** A value's kind, as an error names it. */
    private static String kind(Object v) {
        if (v == null) return "null";
        if (v instanceof java.util.List<?>) return "a list";
        if (v instanceof java.util.Map<?, ?>) return "an object";
        if (v instanceof Boolean) return "a boolean";
        return v.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** {@code shopApi.post /orders → 201 (12 ms)}: one resource call, as the log has it. */
    private void logCall(String resource, String operation, Object target, ResponseObject resp) {
        String what = target instanceof String s && !s.isBlank() ? " " + s : "";
        context.getOutput().line("CALL", resource + "." + operation + what + " → " + resp.status() + " (" + resp.time() + " ms)");
    }

    /** A failure in the script author's words: an assertion's own message, without diagnostics. */
    private static String reasonOf(Throwable e) {
        if (e instanceof StepFailure && e.getCause() != null) return reasonOf(e.getCause());
        if (e instanceof ScriptAssertionError a) return a.reason();
        return e.getMessage();
    }

    /** A failed {@code assert}: its message carries the diagnostics, {@link #reason()} the author's words. */
    public static final class ScriptAssertionError extends AssertionError {
        private final String reason;

        ScriptAssertionError(String message, String reason) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * Runs the registered {@code cleanup} blocks, last first, each to its end or its first failure.
     * Answers the first failure's message; when the script failed already, failures are only warnings.
     */
    private String runCleanups(boolean scriptFailed) {
        String first = null;
        ExecutionContext.Cleanup cleanup;
        while ((cleanup = context.popCleanup()) != null) {
            context.restore(cleanup);
            try {
                for (StatementNode s : cleanup.body()) executeStatement(s);
            } catch (BreakSignal | TopLevelReturnSignal ignored) {
                // ends this cleanup block
            } catch (Exception | AssertionError e) {
                String message = "cleanup: " + e.getMessage();
                if (scriptFailed || first != null) {
                    context.recordWarning(message);
                    context.getOutput().line("WARN", message);
                } else {
                    first = message;
                }
            }
        }
        return first;
    }

    private void executeGoal(GoalNode n) {
        String text = String.valueOf(evaluate(n.text()));
        context.addGoal(text);
        context.getOutput().line("GOAL", text);
    }

    /**
     * {@code step "name" { ... }}: runs the block as one named part of the flow. A failure inside it
     * fails the script with a message that names the step.
     */
    private void executeStep(StepNode n) {
        String name = String.valueOf(evaluate(n.name()));
        context.getOutput().line("STEP", name);
        long start = System.currentTimeMillis();
        try {
            for (StatementNode s : n.body()) executeStatement(s);
        } catch (BreakSignal | TopLevelReturnSignal | MockReturnSignal signal) {
            context.addStep(new StepResult(name, true, System.currentTimeMillis() - start, null));
            throw signal;
        } catch (Exception | AssertionError e) {
            String message = e.getMessage();
            context.addStep(new StepResult(name, false, System.currentTimeMillis() - start, message));
            throw new StepFailure("Step \"" + name + "\" failed: " + message, e);
        }
        context.addStep(new StepResult(name, true, System.currentTimeMillis() - start, null));
    }

    /** A failure inside a step, already named after it. */
    public static final class StepFailure extends RuntimeException {
        StepFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** {@code include "file"}: parses another script and runs it here, in the same context. */
    private void executeInclude(IncludeNode n) {
        java.nio.file.Path file = context.getScriptDir().resolve(n.path()).normalize();
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new RuntimeException("include at line " + n.line() + ": no file " + file);
        }
        ScriptNode included;
        try {
            included = org.bbrun.parser.ScriptParser.parse(java.nio.file.Files.readString(file));
        } catch (java.io.IOException e) {
            throw new RuntimeException("include at line " + n.line() + ": cannot read " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new RuntimeException("include " + n.path() + ": " + e.getMessage(), e);
        }
        context.pushScriptDir(file.getParent());
        try {
            for (StatementNode s : included.statements()) executeStatement(s);
        } finally {
            context.popScriptDir();
        }
    }

    public void executeStatement(StatementNode stmt) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Cancelled before line " + stmt.line());
        }
        if (stmt instanceof VariableNode n) {
            Object val = evaluate(n.value());
            context.setVariable(n.name(), val);
        } else if (stmt instanceof TypedVariableDeclNode n) {
            executeTypedVariableDecl(n);
        } else if (stmt instanceof VerbRequestNode n) {
            executeVerbRequest(n);
        } else if (stmt instanceof MapNode n) {
            executeMap(n);
        } else if (stmt instanceof ValidateNode n) {
            executeValidate(n);
        } else if (stmt instanceof AssertNode n) {
            executeAssert(n);
        } else if (stmt instanceof WarnNode n) {
            executeWarn(n);
        } else if (stmt instanceof PrintNode n) {
            Object val = evaluate(n.message());
            context.getOutput().line("INFO", formatOutput(val));
        } else if (stmt instanceof IfNode n) {
            executeIf(n);
        } else if (stmt instanceof ForNode n) {
            executeFor(n);
        } else if (stmt instanceof BreakNode n) {
            throw new BreakSignal();
        } else if (stmt instanceof ReturnNode n) {
            executeReturn(n);
        } else if (stmt instanceof AbortNode n) {
            executeAbort(n);
        } else if (stmt instanceof SetStatusNode n) {
            executeSetStatus(n);
        } else if (stmt instanceof SetHeaderNode n) {
            executeSetHeader(n);
        } else if (stmt instanceof GoalNode n) {
            executeGoal(n);
        } else if (stmt instanceof StepNode n) {
            executeStep(n);
        } else if (stmt instanceof CleanupNode n) {
            context.pushCleanup(n.body());
        } else if (stmt instanceof IncludeNode n) {
            executeInclude(n);
        } else if (stmt instanceof ExpressionStatementNode n) {
            evaluate(n.expression());
        }
    }

    /**
     * {@code return <expr>} inside a mock handler script is the simple form of
     * a response: it sets the response body (status 200) and stops the script.
     * Equivalent to {@code respond(200, expr)}. At the top level of a script it
     * ends the flow and yields its value ({@link ExecutionResult#value()}) —
     * the embedded integration-flow contract.
     */
    private void executeReturn(ReturnNode n) {
        Object value = n.value() != null ? evaluate(n.value()) : null;
        if (context.getMockSink() != null) {
            // Inside a mock handler script: `return <expr>` is shorthand for respond(200, expr)
            context.getMockSink().respond(200, value, Map.of());
            throw new MockReturnSignal();
        }
        // Top level (embedded integration flow): ends the flow, yields the value
        throw new TopLevelReturnSignal(value);
    }

    /**
     * `abort` (with an optional reason expression) throws an error and ends
     * the execution prematurely.
     */
    private void executeAbort(AbortNode n) {
        String reason = null;
        if (n.message() != null) {
            Object value = evaluate(n.message());
            reason = value != null ? String.valueOf(value) : "";
        }
        String message = "Aborted at line " + n.line()
                + (reason != null && !reason.isEmpty() ? ": " + reason : "");
        throw new RuntimeException(message);
    }

    /**
     * {@code status(code)} sets the HTTP status code for the top-level return
     * value. This is an integration-flow signal: the Java host reads it from
     * {@link ExecutionResult#statusCode()} and sends it as the HTTP response
     * status. Without this call, the host infers status: non-null value → 200,
     * null → 204.
     */
    private void executeSetStatus(SetStatusNode n) {
        Object val = evaluate(n.statusValue());
        if (val instanceof Number num) {
            context.setStatusCode(num.intValue());
        } else {
            throw new RuntimeException("'status' expects a number at line " + n.line());
        }
    }

    /**
     * {@code header(name, value)} adds a response header to the top-level
     * return. The host applies these to its HTTP response via
     * {@link ExecutionResult#responseHeaders()}.
     */
    private void executeSetHeader(SetHeaderNode n) {
        Object name = evaluate(n.name());
        Object value = evaluate(n.value());
        if (name == null) {
            throw new RuntimeException("'header' name must not be null at line " + n.line());
        }
        context.setResponseHeader(String.valueOf(name), value != null ? String.valueOf(value) : "");
    }

    /**
     * A resource call in expression position (`paymentsApi.get("/payments")`,
     * `db.query("select ...")`, `queue.receive()`). Runs the request (so
     * `response` stays updated) and yields the raw response body as its value.
     */
    private Object executeResourceCallExpression(String resourceName, String operation, List<ExpressionNode> args) {
        List<Object> evaluated = new ArrayList<>(args.size());
        for (ExpressionNode arg : args) {
            Object value = evaluate(arg);
            if (value instanceof String s) {
                value = interpolatePath(s);
            }
            evaluated.add(value);
        }
        ResponseObject resp = context.getResourceContext().invoke(resourceName, operation, evaluated, scriptHeaders());
        logCall(resourceName, operation, evaluated.isEmpty() ? null : evaluated.get(0), resp);
        context.setVariable("response", resp);
        context.setLastResponseResource(resourceName);
        context.recordRequest();
        return resp.body();
    }

    private java.util.Map<String, String> scriptHeaders() {
        String name = context.getScriptName();
        return name != null ? java.util.Map.of("x-bbrun-script", name) : null;
    }

    private void executeVerbRequest(VerbRequestNode n) {
        String targetRes = n.targetResource();
        if (targetRes == null || targetRes.isBlank()) {
            throw new RuntimeException("Verb requests must target a resource: use '" + n.verb() +
                    " resource.\"/path\"' (like resource.get(\"/path\"))");
        }
        String path = interpolatePath(n.path());
        Object body = n.body() != null ? evaluate(n.body()) : null;
        List<Object> args = new ArrayList<>();
        args.add(path);
        if (body != null) {
            args.add(body);
        }
        ResponseObject resp = context.getResourceContext().invoke(targetRes, n.verb(), args, scriptHeaders());
        logCall(targetRes, n.verb(), path, resp);
        context.setVariable("response", resp);
        context.setLastResponseResource(targetRes);
        context.recordRequest();
    }

    private void executeMap(MapNode n) {
        Object source = evaluate(n.source());
        String varName = n.varName();
        if (varName == null || varName.isBlank()) {
            org.bbrun.validation.Mapping mapping = context.getMappingRegistry().getMapping(n.mappingKey());
            varName = decapitalize(mapping.targetModel() != null ? mapping.targetModel() : mapping.name());
        }
        context.setVariable(varName, context.getMappingRegistry().apply(n.mappingKey(), source));
    }

    /**
     * A typed declaration (`PaymentIntent p = paymentsApi.get(...)` or
     * `PaymentIntent[] list = paymentsApi.get(...)`) binds the declared model.
     * When the value comes from the response of a resource that maps that model,
     * the raw response is mapped into our model first; the mapped value is then
     * validated against the model. If either step fails, the statement throws.
     */
    private void executeTypedVariableDecl(TypedVariableDeclNode n) {
        DomainModel model = context.getDrefBridge().getRegistry().getModel(n.typeName());
        if (model == null) {
            throw new RuntimeException("Unknown model type '" + n.typeName() + "' at line " + n.line());
        }

        Object value = evaluate(n.value());

        if (isResponseDerived(n.value())) {
            String resName = context.getLastResponseResource();
            if (resName != null) {
                value = applyMappingFor(resName, n.typeName(), value);
            }
        }

        if (n.array()) {
            if (!(value instanceof List<?> list)) {
                throw new RuntimeException("Expected an array response for '" + n.typeName() + "[] " + n.name() + "' at line " + n.line());
            }
            for (Object item : list) {
                validateTyped(item, n.typeName(), n.line());
            }
        } else {
            validateTyped(value, n.typeName(), n.line());
        }

        context.setVariable(n.name(), value);
    }

    private void validateTyped(Object value, String modelName, int line) {
        try {
            context.getDrefBridge().validateOrThrow(value, modelName);
        } catch (DrefValidationException e) {
            throw new RuntimeException("Typed assignment to '" + modelName + "' failed at line " + line + ": " + e.getMessage(), e);
        }
    }

    private Object applyMappingFor(String resourceName, String modelName, Object value) {
        org.bbrun.validation.Mapping mapping = context.getMappingRegistry().findMappingFor(resourceName, modelName);
        if (mapping == null) {
            return value;
        }
        org.bbrun.validation.MappingRegistry registry = context.getMappingRegistry();
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>();
            for (Object item : list) {
                mapped.add(registry.apply(mapping, item));
            }
            return mapped;
        }
        return registry.apply(mapping, value);
    }

    private boolean isResponseDerived(ExpressionNode expression) {
        return isResourceMethodCall(expression) || isRawResponseBody(expression);
    }

    /**
     * True when the expression is a method call made on the name of a configured
     * resource (e.g. {@code paymentsApi.get("/payments")}): value comes from the
     * raw response of a resource call.
     */
    private boolean isResourceMethodCall(ExpressionNode expression) {
        return expression instanceof MethodCallNode n
                && n.object() instanceof IdentifierNode id
                && context.getResourceContext().hasHandler(id.name());
    }

    private String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private void executeValidate(ValidateNode n) {
        Object target = resolveForModel(n.target(), n.modelName());
        try {
            context.getDrefBridge().validateOrThrow(target, n.modelName());
            context.recordPass();
        } catch (DrefValidationException e) {
            context.recordFail();
            String prefix = n.message() != null ? n.message() + "\n" : "";
            throw new RuntimeException(prefix + e.getMessage(), e);
        }
    }

    private void executeAssert(AssertNode n) {
        Object left = null;
        Object right = null;
        boolean comparison = n.condition() instanceof BinaryOpNode b
                && List.of("==", "!=", "<", ">", "<=", ">=").contains(b.operator());
        Object res;
        if (comparison) {
            BinaryOpNode b = (BinaryOpNode) n.condition();
            left = evaluate(b.left());
            right = evaluate(b.right());
            res = compareValues(b.operator(), left, right);
        } else {
            res = evaluate(n.condition());
        }
        if (isTruthy(res)) {
            context.recordPass();
            return;
        }
        context.recordFail();
        StringBuilder msg = new StringBuilder();
        msg.append(n.message() != null ? n.message() : "Assertion failed");
        msg.append(" (line ").append(n.line());
        if (n.source() != null) msg.append(": ").append(n.source());
        msg.append(")");
        if (comparison) {
            msg.append("; got ").append(shortText(left));
            BinaryOpNode b = (BinaryOpNode) n.condition();
            if (!(b.right() instanceof LiteralNode)) msg.append(" against ").append(shortText(right));
        }
        if (context.getVariable("response") instanceof ResponseObject r) {
            msg.append("; last response ").append(r.status()).append(" ").append(shortText(r.body()));
        }
        String reason = n.message() != null ? n.message()
                : "Assertion failed (line " + n.line() + (n.source() != null ? ": " + n.source() : "") + ")";
        throw new ScriptAssertionError(msg.toString(), reason);
    }

    private Object compareValues(String op, Object left, Object right) {
        return evaluateBinaryOp(new BinaryOpNode(op, new LiteralNode(left, 0), new LiteralNode(right, 0), 0));
    }

    private String shortText(Object o) {
        String text = o instanceof String s ? "\"" + s + "\"" : formatOutput(o);
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private void executeWarn(WarnNode n) {
        Object res = evaluate(n.condition());
        if (!isTruthy(res)) {
            String msg = n.message() != null ? n.message() : "Warning condition failed at line " + n.line();
            context.recordWarning(msg);
            context.getOutput().line("WARN", msg);
        }
    }

    private void executeIf(IfNode n) {
        Object cond = evaluate(n.condition());
        if (isTruthy(cond)) {
            for (StatementNode s : n.thenBody()) executeStatement(s);
        } else if (n.elseBody() != null) {
            for (StatementNode s : n.elseBody()) executeStatement(s);
        }
    }

    private void executeFor(ForNode n) {
        try {
            if (n.start() != null) {
                int from = toInt(evaluate(n.start()));
                int to = toInt(evaluate(n.end()));
                for (int v = from; v <= to; v++) {
                    context.setVariable(n.varName(), v);
                    for (StatementNode s : n.body()) executeStatement(s);
                }
            } else {
                Object coll = evaluate(n.collection());
                if (coll instanceof List<?> list) {
                    for (Object el : list) {
                        context.setVariable(n.varName(), el);
                        for (StatementNode s : n.body()) executeStatement(s);
                    }
                } else if (coll instanceof Map<?, ?> map) {
                    for (Object el : map.values()) {
                        context.setVariable(n.varName(), el);
                        for (StatementNode s : n.body()) executeStatement(s);
                    }
                } else {
                    context.setVariable(n.varName(), coll);
                    for (StatementNode s : n.body()) executeStatement(s);
                }
            }
        } catch (BreakSignal b) {
            // break exits the innermost for loop
        }
    }

    private int toInt(Object value) {
        return value instanceof Number num ? num.intValue() : 0;
    }

    /**
     * True when the number carries a fractional part in its type, so mixed
     * integral/fractional math must run in {@code double}, not {@code long}.
     * Fastjson parses JSON decimals as BigDecimal (not Double), so this is
     * broader than an {@code instanceof Double} check.
     */
    private static boolean isFloatingPoint(Number n) {
        return n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal;
    }

    // ========== Expression Evaluation ==========

    public Object evaluate(ExpressionNode expr) {
        if (expr == null) return null;

        if (expr instanceof LiteralNode n) {
            return n.value();
        } else if (expr instanceof IdentifierNode n) {
            return evaluateIdentifier(n.name());
        } else if (expr instanceof MemberAccessNode n) {
            return evaluateMemberAccess(n);
        } else if (expr instanceof IndexAccessNode n) {
            return evaluateIndexAccess(n);
        } else if (expr instanceof FunctionCallNode n) {
            return evaluateFunctionCall(n);
        } else if (expr instanceof MethodCallNode n) {
            return evaluateMethodCall(n);
        } else if (expr instanceof BinaryOpNode n) {
            return evaluateBinaryOp(n);
        } else if (expr instanceof UnaryOpNode n) {
            return evaluateUnaryOp(n);
        } else if (expr instanceof MatchesModelNode n) {
            Object val = resolveForModel(n.expression(), n.modelName());
            return context.getDrefBridge().matches(val, n.modelName());
        } else if (expr instanceof ContainsNode n) {
            return evaluateContains(n);
        } else if (expr instanceof ObjectLiteralNode n) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, ExpressionNode> entry : n.entries().entrySet()) {
                map.put(entry.getKey(), evaluate(entry.getValue()));
            }
            return map;
        } else if (expr instanceof ArrayLiteralNode n) {
            List<Object> list = new ArrayList<>();
            for (ExpressionNode item : n.items()) {
                list.add(evaluate(item));
            }
            return list;
        } else if (expr instanceof PathNode n) {
            return interpolatePath(n.rawPath());
        }

        return null;
    }

    private Object evaluateIdentifier(String name) {
        if (context.hasVariable(name)) {
            return context.getVariable(name);
        }
        return null;
    }

    private Object evaluateMemberAccess(MemberAccessNode n) {
        Object obj = evaluate(n.object());
        String member = n.member();
        if (obj instanceof ResponseObject resp) {
            return switch (member) {
                case "status" -> resp.status();
                case "headers" -> resp.headers();
                case "body" -> resp.body();
                case "time" -> resp.time();
                default -> null;
            };
        }
        if (obj instanceof List<?> list) {
            if ("length".equals(member) || "size".equals(member)) return list.size();
            return null;
        }
        if (obj instanceof String s) {
            return "length".equals(member) ? s.length() : null;
        }
        if (obj instanceof Map<?, ?> map) {
            return map.get(member);
        }
        return null;
    }

    private Object evaluateIndexAccess(IndexAccessNode n) {
        Object obj = evaluate(n.object());
        Object idx = evaluate(n.index());
        if (obj instanceof List<?> list && idx instanceof Number num) {
            int i = num.intValue();
            if (i >= 0 && i < list.size()) return list.get(i);
        }
        if (obj instanceof Map<?, ?> map) {
            return map.get(idx);
        }
        return null;
    }

    private Object evaluateMethodCall(MethodCallNode n) {
        // A method call on the name of a configured resource (`payments.get(...)`,
        // `db.query(...)`, `queue.receive()`) dispatches to the resource handler,
        // unless a variable shadows the resource name.
        if (n.object() instanceof IdentifierNode id
                && !context.hasVariable(id.name())
                && context.getResourceContext().hasHandler(id.name())) {
            return executeResourceCallExpression(id.name(), n.methodName(), n.arguments());
        }
        if (n.object() instanceof IdentifierNode id && !context.hasVariable(id.name())) {
            context.getResourceContext().getHandler(id.name()); // names the configured resources
        }
        Object obj = evaluate(n.object());
        String method = n.methodName();
        if (obj instanceof String s) {
            List<Object> a = new ArrayList<>();
            for (ExpressionNode arg : n.arguments()) a.add(evaluate(arg));
            return Builtins.stringMethod(s, method, a, n.line());
        }
        if (obj instanceof List<?> l && "length".equals(method)) {
            return l.size();
        }
        throw new RuntimeException("No method " + method + "() on " + describe(obj) + " at line " + n.line());
    }

    private Object evaluateFunctionCall(FunctionCallNode n) {
        String name = n.name();
        List<ExpressionNode> args = n.arguments();
        return switch (name) {
            case "uuid" -> UUID.randomUUID().toString();
            case "now" -> System.currentTimeMillis();
            case "random" -> {
                if (args.isEmpty()) yield random.nextDouble();
                Object maxObj = evaluate(args.get(0));
                yield (maxObj instanceof Number num) ? random.nextInt(num.intValue()) : random.nextInt(1000);
            }
            case "randomString" -> {
                int len = !args.isEmpty() && evaluate(args.get(0)) instanceof Number num ? num.intValue() : 8;
                yield generateRandomString(len);
            }
            case "env" -> {
                if (args.isEmpty()) yield null;
                String varName = String.valueOf(evaluate(args.get(0)));
                String envVal = System.getenv(varName);
                if (envVal != null) yield envVal;
                yield (args.size() > 1) ? evaluate(args.get(1)) : null;
            }
            case "respond" -> {
                MockResponseSink sink = context.getMockSink();
                if (sink == null) {
                    throw new RuntimeException("'respond' can only be used inside a mock handler script");
                }
                if (args.size() < 2) {
                    throw new RuntimeException("'respond' expects respond(status, body[, headers])");
                }
                Object statusObj = evaluate(args.get(0));
                if (!(statusObj instanceof Number num)) {
                    throw new RuntimeException("'respond' status must be a number");
                }
                Object bodyObj = evaluate(args.get(1));
                Map<String, String> respHeaders = Map.of();
                if (args.size() > 2) {
                    Object h = evaluate(args.get(2));
                    Map<String, String> hdrs = new LinkedHashMap<>();
                    if (h instanceof Map<?, ?> hm) {
                        for (Map.Entry<?, ?> e : hm.entrySet()) hdrs.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    } else if (h != null) {
                        throw new RuntimeException("'respond' headers must be a map");
                    }
                    respHeaders = hdrs;
                }
                sink.respond(num.intValue(), bodyObj, respHeaders);
                yield null;
            }
            default -> Builtins.call(this, name, args);
        };
    }

    private Object evaluateBinaryOp(BinaryOpNode n) {
        Object left = evaluate(n.left());
        Object right = evaluate(n.right());
        String op = n.operator();

        return switch (op) {
            case "==" -> sameValue(left, right);
            case "!=" -> !sameValue(left, right);
            case "and" -> isTruthy(left) && isTruthy(right);
            case "or" -> isTruthy(left) || isTruthy(right);
            case "<" -> compare(left, right) < 0;
            case "<=" -> compare(left, right) <= 0;
            case ">" -> compare(left, right) > 0;
            case ">=" -> compare(left, right) >= 0;
            case "+" -> {
                if (left instanceof String || right instanceof String) {
                    yield String.valueOf(left) + String.valueOf(right);
                }
                if (left instanceof Number l && right instanceof Number r) {
                    if (isFloatingPoint(l) || isFloatingPoint(r)) {
                        yield l.doubleValue() + r.doubleValue();
                    }
                    yield l.longValue() + r.longValue();
                }
                if (left instanceof java.util.List<?> l && right instanceof java.util.List<?> r) {
                    java.util.List<Object> joined = new java.util.ArrayList<>(l);
                    joined.addAll(r);
                    yield joined;
                }
                throw new RuntimeException("+ adds numbers, joins strings or joins lists, not " + kind(left) + " and " + kind(right));
            }
            case "-" -> {
                if (left instanceof Number l && right instanceof Number r) {
                    if (isFloatingPoint(l) || isFloatingPoint(r)) {
                        yield l.doubleValue() - r.doubleValue();
                    }
                    yield l.longValue() - r.longValue();
                }
                yield null;
            }
            case "*" -> {
                if (left instanceof Number l && right instanceof Number r) {
                    if (isFloatingPoint(l) || isFloatingPoint(r)) {
                        yield l.doubleValue() * r.doubleValue();
                    }
                    yield l.longValue() * r.longValue();
                }
                yield null;
            }
            case "/" -> {
                if (left instanceof Number l && right instanceof Number r) {
                    yield l.doubleValue() / r.doubleValue();
                }
                yield null;
            }
            default -> null;
        };
    }

    private Object evaluateUnaryOp(UnaryOpNode n) {
        Object operand = evaluate(n.operand());
        if ("not".equals(n.operator())) {
            return !isTruthy(operand);
        }
        return null;
    }

    private Object evaluateContains(ContainsNode n) {
        Object collection = evaluate(n.collection());
        Object item = evaluate(n.item());
        boolean res = false;
        if (collection instanceof String s && item != null) {
            res = s.contains(String.valueOf(item));
        } else if (collection instanceof Collection<?> col) {
            for (Object el : col) {
                if (sameValue(el, item)) {
                    res = true;
                    break;
                }
            }
        } else if (collection instanceof Map<?, ?> map) {
            res = item != null && map.containsKey(String.valueOf(item));
        }
        return n.negated() ? !res : res;
    }

    /**
     * Equality as {@code ==} has it: numbers by value whatever their Java type (a JSON 1 equals a
     * script's 1), lists element by element, objects key by key.
     */
    static boolean sameValue(Object a, Object b) {
        if (a instanceof Number l && b instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue()) == 0;
        }
        if (a instanceof List<?> l && b instanceof List<?> r) {
            if (l.size() != r.size()) return false;
            for (int i = 0; i < l.size(); i++) {
                if (!sameValue(l.get(i), r.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map<?, ?> l && b instanceof Map<?, ?> r) {
            if (l.size() != r.size()) return false;
            for (Map.Entry<?, ?> e : l.entrySet()) {
                if (!r.containsKey(e.getKey()) || !sameValue(e.getValue(), r.get(e.getKey()))) return false;
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "a string";
        if (o instanceof Number) return "a number";
        if (o instanceof Boolean) return "a boolean";
        if (o instanceof List<?>) return "a list";
        if (o instanceof Map<?, ?>) return "an object";
        return o.getClass().getSimpleName();
    }

    private int compare(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        if (left instanceof Comparable c && right != null && left.getClass().isAssignableFrom(right.getClass())) {
            return c.compareTo(right);
        }
        return 0;
    }

    private boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof Collection<?> c) return !c.isEmpty();
        return true;
    }

    /**
     * Resolves the value to validate/match against a model. When the expression
     * is exactly the current raw response of a resource bound to a mapping that
     * targets the model, the response is mapped into our domain first — mapping
     * becomes fully implicit.
     */
    private Object resolveForModel(ExpressionNode expression, String modelName) {
        if (isRawResponseBody(expression) && modelName != null) {
            String resName = context.getLastResponseResource();
            if (resName != null) {
                org.bbrun.validation.Mapping mapping =
                        context.getMappingRegistry().findMappingFor(resName, modelName);
                if (mapping != null) {
                    ResponseObject resp = context.getVariable("response") instanceof ResponseObject r ? r : null;
                    if (resp != null) {
                        return context.getMappingRegistry().apply(mapping, resp.body());
                    }
                }
            }
        }
        return evaluate(expression);
    }

    private boolean isRawResponseBody(ExpressionNode expression) {
        if (!(expression instanceof MemberAccessNode n)) return false;
        if (!"body".equals(n.member())) return false;
        return n.object() instanceof org.bbrun.ast.Expressions.IdentifierNode id && "response".equals(id.name());
    }

    public String interpolatePath(String rawPath) {
        if (rawPath == null) return "";
        Matcher matcher = INTERPOLATION_PATTERN.matcher(rawPath);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object val = resolveExpressionString(varName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(val != null ? val : "")));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolveExpressionString(String expr) {
        String[] parts = expr.split("\\.");
        Object curr = context.getVariable(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (curr instanceof ResponseObject resp) {
                curr = switch (parts[i]) {
                    case "status" -> resp.status();
                    case "body" -> resp.body();
                    case "headers" -> resp.headers();
                    case "time" -> resp.time();
                    default -> null;
                };
            } else if (curr instanceof Map<?, ?> map) {
                curr = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return curr;
    }

    private String generateRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String formatOutput(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return JSON.toJSONString(o);
    }
}
