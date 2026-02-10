package org.bbrun.interpreter;

import org.bbrun.RequestMetric;
import org.bbrun.Warning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of execution progress at a point in time.
 */
public record ExecutionProgress(
        int totalStatements,
        int completedStatements,
        int passedAssertions,
        int failedAssertions,
        List<Warning> warnings,
        List<RequestMetric> requests,
        ExecutionState state,
        String currentStatement) {
    public enum ExecutionState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public double percentComplete() {
        if (totalStatements == 0)
            return 0;
        return (double) completedStatements / totalStatements * 100;
    }

    public static ExecutionProgress initial() {
        return new ExecutionProgress(
                0, 0, 0, 0,
                Collections.emptyList(),
                Collections.emptyList(),
                ExecutionState.PENDING,
                null);
    }

    public ExecutionProgress withStatement(String statement, int completed, int total) {
        return new ExecutionProgress(
                total, completed, passedAssertions, failedAssertions,
                warnings, requests, ExecutionState.RUNNING, statement);
    }

    public ExecutionProgress withAssertion(boolean passed, Warning warning) {
        List<Warning> newWarnings = warnings;
        if (warning != null) {
            newWarnings = new ArrayList<>(warnings);
            newWarnings.add(warning);
        }
        return new ExecutionProgress(
                totalStatements, completedStatements,
                passed ? passedAssertions + 1 : passedAssertions,
                passed ? failedAssertions : failedAssertions + 1,
                newWarnings, requests, state, currentStatement);
    }

    public ExecutionProgress withRequest(RequestMetric metric) {
        List<RequestMetric> newRequests = new ArrayList<>(requests);
        newRequests.add(metric);
        return new ExecutionProgress(
                totalStatements, completedStatements, passedAssertions, failedAssertions,
                warnings, newRequests, state, currentStatement);
    }

    public ExecutionProgress withState(ExecutionState newState) {
        return new ExecutionProgress(
                totalStatements, completedStatements, passedAssertions, failedAssertions,
                warnings, requests, newState, currentStatement);
    }
}
