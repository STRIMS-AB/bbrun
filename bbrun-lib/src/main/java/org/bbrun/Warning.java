package org.bbrun;

/**
 * Warning collected during script execution.
 */
public record Warning(
        String message,
        int line,
        String expression) {
}
