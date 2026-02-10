package org.bbrun.interpreter;

import org.bbrun.ExecutionResult;
import org.bbrun.RequestMetric;
import org.bbrun.Warning;
import org.bbrun.ast.ScriptNode;
import org.bbrun.ast.StatementNode;
import org.bbrun.events.EventListener;
import org.bbrun.spi.HttpClient;
import org.bbrun.spi.ProviderRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Interprets and executes BBRun AST.
 * 
 * Uses SPI to discover the best available HTTP client and executor.
 */
public class Interpreter {

    private final ExecutionOptions options;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public Interpreter(ExecutionOptions options) {
        this.options = options;

        // Use SPI to get the best available implementations
        ProviderRegistry registry = ProviderRegistry.getInstance();
        this.executor = registry.createExecutor(options);
        this.httpClient = registry.createHttpClient(options);
    }

    /**
     * Execute script asynchronously.
     */
    public ExecutionHandle executeAsync(ScriptNode script) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        ExecutionHandle handle = new ExecutionHandle(future);

        executor.submit(() -> {
            try {
                ExecutionResult result = execute(script, handle);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return handle;
    }

    /**
     * Execute script synchronously.
     */
    public ExecutionResult execute(ScriptNode script, ExecutionHandle handle) {
        long startTime = System.currentTimeMillis();

        // Create execution context and statement executor
        Context context = new Context(options);
        StatementExecutor stmtExecutor = new StatementExecutor(context, httpClient, handle);

        // Notify script start
        int totalStatements = script.statements().size();
        if (handle != null) {
            handle.updateProgress(ExecutionProgress.initial()
                    .withStatement("Starting", 0, totalStatements));

            for (EventListener listener : handle.getListeners()) {
                listener.onScriptStart(new EventListener.ScriptEvent(
                        script.path(), totalStatements));
            }
        }

        // Execute each statement
        int completed = 0;
        boolean failed = false;

        for (StatementNode statement : script.statements()) {
            // Check for cancellation
            if (handle != null && handle.isCancelled()) {
                return buildResult(stmtExecutor, startTime, totalStatements,
                        new InterruptedException("Execution cancelled"));
            }

            // Update progress
            completed++;
            if (handle != null) {
                handle.updateProgress(handle.poll()
                        .withStatement(getStatementName(statement), completed, totalStatements));
            }

            // Execute statement
            boolean success = stmtExecutor.execute(statement);
            if (!success) {
                failed = true;
                if (options.isFailFast()) {
                    break;
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Build result
        ExecutionResult result = buildResult(stmtExecutor, startTime, totalStatements, null);

        // Update final state
        if (handle != null) {
            handle.updateProgress(handle.poll()
                    .withState(stmtExecutor.getFailedAssertions() == 0
                            ? ExecutionProgress.ExecutionState.COMPLETED
                            : ExecutionProgress.ExecutionState.FAILED));

            for (EventListener listener : handle.getListeners()) {
                listener.onScriptComplete(new EventListener.CompletionEvent(
                        stmtExecutor.getFailedAssertions() == 0,
                        stmtExecutor.getPassedAssertions(),
                        stmtExecutor.getFailedAssertions(),
                        duration));
            }
        }

        return result;
    }

    private ExecutionResult buildResult(StatementExecutor executor, long startTime,
            int totalStatements, Exception error) {
        long duration = System.currentTimeMillis() - startTime;

        if (error != null || executor.getFailedAssertions() > 0) {
            return ExecutionResult.failure(
                    totalStatements,
                    executor.getPassedAssertions(),
                    executor.getFailedAssertions(),
                    executor.getWarnings(),
                    executor.getRequests(),
                    duration,
                    error);
        }

        return ExecutionResult.success(
                totalStatements,
                executor.getPassedAssertions(),
                executor.getWarnings(),
                executor.getRequests(),
                duration);
    }

    private String getStatementName(StatementNode statement) {
        return statement.getClass().getSimpleName().replace("Node", "");
    }

    /**
     * Get the HTTP client (for direct access if needed).
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Shutdown the interpreter, releasing resources.
     */
    public void shutdown() {
        executor.shutdown();
        httpClient.close();
    }
}
