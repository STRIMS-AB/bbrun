package org.bbrun;

import org.bbrun.interpreter.ExecutionHandle;
import org.bbrun.interpreter.ExecutionOptions;
import org.bbrun.interpreter.Interpreter;
import org.bbrun.parser.ScriptLoader;

import java.nio.file.Path;

/**
 * Main entry point for BBRun script execution.
 * 
 * <pre>{@code
 * BBRun bbrun = new BBRun();
 * ExecutionHandle handle = bbrun.execute(Path.of("test.bbrun"));
 * 
 * // Poll for progress
 * while (!handle.isComplete()) {
 *     ExecutionProgress progress = handle.poll();
 *     System.out.println(progress.completedStatements() + "/" + progress.totalStatements());
 * }
 * 
 * ExecutionResult result = handle.future().get();
 * }</pre>
 */
public class BBRun {

    private final ScriptLoader scriptLoader;

    public BBRun() {
        this.scriptLoader = new ScriptLoader();
    }

    /**
     * Execute a script with default options.
     */
    public ExecutionHandle execute(Path script) {
        return execute(script, new ExecutionOptions());
    }

    /**
     * Execute a script with custom options.
     */
    public ExecutionHandle execute(Path script, ExecutionOptions options) {
        // Load script with _init.bbrun files merged
        var ast = scriptLoader.loadWithInit(script);

        // Create interpreter and start async execution
        var interpreter = new Interpreter(options);
        return interpreter.executeAsync(ast);
    }

    /**
     * Execute a script synchronously (blocking).
     */
    public ExecutionResult executeSync(Path script) {
        return executeSync(script, new ExecutionOptions());
    }

    /**
     * Execute a script synchronously with custom options.
     */
    public ExecutionResult executeSync(Path script, ExecutionOptions options) {
        try {
            return execute(script, options).future().get();
        } catch (Exception e) {
            throw new BBRunException("Execution failed", e);
        }
    }
}
