package org.bbrun.events;

import org.bbrun.RequestMetric;
import org.bbrun.Warning;

/**
 * Listener for execution events.
 */
public interface EventListener {

    /**
     * Called when a script starts executing.
     */
    default void onScriptStart(ScriptEvent event) {
    }

    /**
     * Called before each HTTP request.
     */
    default void onRequestStart(RequestEvent event) {
    }

    /**
     * Called after each HTTP request completes.
     */
    default void onRequestComplete(RequestEvent event) {
    }

    /**
     * Called when an assertion passes.
     */
    default void onAssertionPass(AssertionEvent event) {
    }

    /**
     * Called when an assertion fails.
     */
    default void onAssertionFail(AssertionEvent event) {
    }

    /**
     * Called when a warning is generated.
     */
    default void onWarning(WarningEvent event) {
    }

    /**
     * Called when a script completes.
     */
    default void onScriptComplete(CompletionEvent event) {
    }

    // Event records

    record ScriptEvent(String scriptPath, int totalStatements) {
    }

    record RequestEvent(
            String method,
            String path,
            int status,
            long durationMs,
            boolean success,
            int line) {
        public RequestMetric toMetric() {
            return new RequestMetric(method, path, status, durationMs, success);
        }
    }

    record AssertionEvent(
            String expression,
            boolean passed,
            String message,
            int line) {
    }

    record WarningEvent(Warning warning) {
    }

    record CompletionEvent(
            boolean success,
            int passedAssertions,
            int failedAssertions,
            long durationMs) {
    }
}
