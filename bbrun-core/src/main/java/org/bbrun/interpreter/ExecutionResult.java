package org.bbrun.interpreter;

import java.util.List;
import java.util.Map;

public record ExecutionResult(
        boolean isSuccess,
        long durationMs,
        int passedAssertions,
        int failedAssertions,
        List<String> warnings,
        int requestCount,
        String errorMessage,
        Object value,
        Integer statusCode,
        Map<String, String> responseHeaders,
        List<String> goals,
        List<StepResult> steps,
        String reason
) {
    /*
     * errorMessage: the failure with its diagnostics (line, expression, value found, last
     * response, step), for whoever runs the script. reason: what went wrong in the script
     * author's words (an assertion's own message), for a host to show its callers.
     */
    public ExecutionResult {
        goals = goals != null ? List.copyOf(goals) : List.of();
        steps = steps != null ? List.copyOf(steps) : List.of();
    }

    /** The same result with the script's goals and steps. */
    public ExecutionResult withScenario(List<String> goals, List<StepResult> steps) {
        return new ExecutionResult(isSuccess, durationMs, passedAssertions, failedAssertions, warnings, requestCount,
                errorMessage, value, statusCode, responseHeaders, goals, steps, reason);
    }

    /** The same result with the author's words for its failure. */
    public ExecutionResult withReason(String reason) {
        return new ExecutionResult(isSuccess, durationMs, passedAssertions, failedAssertions, warnings, requestCount,
                errorMessage, value, statusCode, responseHeaders, goals, steps, reason);
    }

    /** The same result, failed with this message (a cleanup that failed after the script passed). */
    public ExecutionResult asFailure(String message) {
        return new ExecutionResult(false, durationMs, passedAssertions, failedAssertions, warnings, requestCount,
                message, value, statusCode, responseHeaders, goals, steps, message);
    }

    public static ExecutionResult success(long durationMs, int passed, int failed, List<String> warnings, int requests) {
        return new ExecutionResult(true, durationMs, passed, failed, warnings, requests, null, null, null, null, null, null, null);
    }

    public static ExecutionResult successWithValue(long durationMs, int passed, int failed, List<String> warnings, int requests, Object value) {
        return new ExecutionResult(true, durationMs, passed, failed, warnings, requests, null, value, null, null, null, null, null);
    }

    public static ExecutionResult successWithOutcome(long durationMs, int passed, int failed, List<String> warnings, int requests,
                                                    Object value, Integer statusCode, Map<String, String> responseHeaders) {
        return new ExecutionResult(true, durationMs, passed, failed, warnings, requests, null, value, statusCode, responseHeaders, null, null, null);
    }

    public static ExecutionResult failure(long durationMs, int passed, int failed, List<String> warnings, int requests, String error) {
        return new ExecutionResult(false, durationMs, passed, failed, warnings, requests, error, null, null, null, null, null, error);
    }
}
