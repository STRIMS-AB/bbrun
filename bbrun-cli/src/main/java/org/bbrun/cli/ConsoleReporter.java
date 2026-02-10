package org.bbrun.cli;

import org.bbrun.ExecutionResult;
import org.bbrun.RequestMetric;
import org.bbrun.Warning;
import org.bbrun.interpreter.ExecutionProgress;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Formats and prints console output with colors and progress bars.
 */
public class ConsoleReporter {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";

    // Symbols
    private static final String CHECK = "✓";
    private static final String CROSS = "✗";
    private static final String WARNING = "⚠";
    private static final String ARROW = "▸";
    private static final String PROGRESS_FILLED = "█";
    private static final String PROGRESS_EMPTY = "░";

    private boolean colorEnabled = true;
    private boolean verbose = false;
    private boolean quiet = false;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void setColorEnabled(boolean enabled) {
        this.colorEnabled = enabled;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public void printHeader(String scriptPath) {
        if (quiet)
            return;
        println("");
        println(color(ARROW, CYAN) + " Running " + color(scriptPath, BOLD));
        println("");
    }

    public void printProgress(ExecutionProgress progress) {
        if (quiet)
            return;

        int completed = progress.completedStatements();
        int total = progress.totalStatements();
        double pct = progress.percentComplete();

        // Build progress bar
        int barWidth = 20;
        int filled = (int) (barWidth * pct / 100);
        StringBuilder bar = new StringBuilder();
        bar.append(color("[", DIM));
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) {
                bar.append(color(PROGRESS_FILLED, BLUE));
            } else {
                bar.append(color(PROGRESS_EMPTY, DIM));
            }
        }
        bar.append(color("]", DIM));
        bar.append(String.format(" %3.0f%% (%d/%d)", pct, completed, total));

        // Overwrite previous line
        System.out.print("\r" + bar);
        System.out.flush();
    }

    public void printRequest(RequestMetric metric) {
        if (quiet)
            return;

        String status = metric.success() ? color(CHECK, GREEN) : color(CROSS, RED);

        String method = color(String.format("%-6s", metric.method()), CYAN);
        String statusCode = metric.status() >= 200 && metric.status() < 300
                ? color(String.valueOf(metric.status()), GREEN)
                : color(String.valueOf(metric.status()), RED);
        String timing = color(metric.durationMs() + "ms", DIM);

        println(String.format(" %s %s %-30s %s   %s",
                status, method, metric.path(), statusCode, timing));
    }

    public void printResult(ExecutionResult result) {
        println("");
        println(color("──────────────────────────────────────────────", DIM));

        if (result.isSuccess()) {
            println(color(" Results: ", BOLD) +
                    color(result.passedAssertions() + " passed", GREEN) +
                    (result.warnings().isEmpty() ? ""
                            : ", " +
                                    color(result.warnings().size() + " warnings", YELLOW)));
        } else {
            println(color(" Results: ", BOLD) +
                    color(result.passedAssertions() + " passed", GREEN) + ", " +
                    color(result.failedAssertions() + " failed", RED) +
                    (result.warnings().isEmpty() ? ""
                            : ", " +
                                    color(result.warnings().size() + " warnings", YELLOW)));
        }

        println(color(" Duration: ", BOLD) + formatDuration(result.durationMs()));
        println(color("──────────────────────────────────────────────", DIM));

        // Print warnings
        if (!result.warnings().isEmpty()) {
            println("");
            println(color(" " + WARNING + " Warnings:", YELLOW));
            for (Warning warning : result.warnings()) {
                println("   • " + warning.message() +
                        color(" (line " + warning.line() + ")", DIM));
            }
        }

        println("");
    }

    public void printResultJson(ExecutionResult result) {
        System.out.println(gson.toJson(result));
    }

    public void printError(Exception e) {
        println("");
        println(color(CROSS + " Error: ", RED) + e.getMessage());
        if (verbose && e.getCause() != null) {
            e.getCause().printStackTrace();
        }
        println("");
    }

    private void println(String message) {
        System.out.println(message);
    }

    private String color(String text, String color) {
        if (!colorEnabled)
            return text;
        return color + text + RESET;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.2fs", ms / 1000.0);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        }
    }
}
