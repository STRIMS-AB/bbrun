package org.bbrun.interpreter;

import java.util.List;

/**
 * Where a script's log lines go while it runs. Levels:
 * <ul>
 *   <li>{@code GOAL}, {@code STEP}: a {@code goal} and the start of a {@code step};</li>
 *   <li>{@code INFO}: a {@code print};</li>
 *   <li>{@code WARN}: a failed {@code warn}, a failed cleanup after a failure;</li>
 *   <li>{@code CALL}: a resource or host service call and its answer ({@code shopApi.post /orders → 201 (12 ms)});</li>
 *   <li>{@code DONE} or {@code ERROR}: how the script ended, last.</li>
 * </ul>
 * Sinks: {@link #STANDARD} (standard output), {@link FileLog} (a file), {@link LogCollector}
 * (kept in memory, read while the script runs), any lambda, and {@link #both} to send to several.
 */
@FunctionalInterface
public interface ScriptOutput {

    ScriptOutput STANDARD = (level, message) -> System.out.println(message);

    /** Sends nothing anywhere. */
    ScriptOutput NONE = (level, message) -> { };

    void line(String level, String message);

    /** Every line to each of {@code outputs}, in order; one that throws does not stop the others. */
    static ScriptOutput both(ScriptOutput... outputs) {
        List<ScriptOutput> all = List.of(outputs);
        return (level, message) -> {
            for (ScriptOutput o : all) {
                try {
                    o.line(level, message);
                } catch (RuntimeException ignored) {
                    // a sink that fails (a full disk) must not fail the script
                }
            }
        };
    }
}
