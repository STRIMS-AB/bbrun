package org.bbrun.interpreter;

/**
 * One line of a script's log: its number in the run ({@code seq}, from 1), when it was written
 * (epoch milliseconds), its level (see {@link ScriptOutput}) and its text.
 */
public record LogEntry(long seq, long at, String level, String message) {
}
