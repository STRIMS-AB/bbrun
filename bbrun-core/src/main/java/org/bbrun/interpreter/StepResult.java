package org.bbrun.interpreter;

/**
 * One {@code step "name" { ... }} of a script as it ran: its name, whether it passed, how long it
 * took, and the failure's message when it did not pass.
 */
public record StepResult(String name, boolean passed, long durationMs, String error) {
}
