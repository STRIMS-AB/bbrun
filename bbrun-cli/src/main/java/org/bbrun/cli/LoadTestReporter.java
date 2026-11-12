package org.bbrun.cli;

import org.bbrun.load.LiveStats;
import org.bbrun.load.LoadSample;
import org.bbrun.load.LoadTestConfig;
import org.bbrun.load.LoadTestReport;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Renders the {@code bbrun load} command.  While a load test runs it paints a
 * live, in-place colored progress panel; when the run finishes it prints a
 * summary report.  In non-interactive terminals (pipes, CI) it degrades to a
 * single-line periodic update.
 */
public class LoadTestReporter {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String DIM = "\u001B[2m";

    private static final int BAR_SEGMENTS = 22;
    private static final String BAR_FILL = "\u2588";
    private static final String BAR_EMPTY = "\u2591";

    private final PrintStream out;
    private final boolean color;
    private final boolean live;
    private final int width;

    private LoadTestConfig config;
    private int paintedLines;

    public LoadTestReporter(boolean colorEnabled, boolean live, int width) {
        this(System.out, colorEnabled, live, width);
    }

    public LoadTestReporter(PrintStream out, boolean colorEnabled, boolean live, int width) {
        this.out = out;
        this.color = colorEnabled;
        this.live = live;
        this.width = Math.max(40, width);
    }

    /**
     * Detects terminal capabilities without pulling in native helpers: an
     * interactive console plus a non-dumb TERM means we can repaint in place
     * and emit ANSI color; otherwise we fall back to periodic single-line
     * updates so piped output and CI stay clean.
     */
    public static LoadTestReporter forTerminal(boolean colorEnabled) {
        boolean tty = System.console() != null;
        String term = System.getenv("TERM");
        boolean ansi = tty && term != null && !"dumb".equalsIgnoreCase(term);
        int width = 80;
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                width = Integer.parseInt(columns.trim());
            } catch (NumberFormatException ignored) {
                // fall back to 80
            }
        }
        return new LoadTestReporter(colorEnabled && ansi, tty, width);
    }

    public void printHeader(LoadTestConfig cfg) {
        this.config = cfg;
        StringBuilder sb = new StringBuilder();
        sb.append(color(BOLD + CYAN, "\u26A1 BBRun Load Test")).append('\n');
        sb.append(color(DIM, "   Script: ")).append(cfg.script()).append('\n');
        sb.append(color(DIM, "   Users:  ")).append(cfg.users());
        if (cfg.iterations() > 0) {
            sb.append(color(DIM, "   Iterations: ")).append(cfg.iterations());
        }
        if (cfg.duration() != null) {
            sb.append(color(DIM, "   Duration: ")).append(formatDuration(cfg.duration().toMillis()));
        }
        if (cfg.rampUp() != null && !cfg.rampUp().isZero()) {
            sb.append(color(DIM, "   Ramp-up: ")).append(formatDuration(cfg.rampUp().toMillis()));
        }
        if (cfg.environment() != null && !cfg.environment().isBlank()) {
            sb.append(color(DIM, "   Env: ")).append(cfg.environment());
        }
        if (cfg.configPath() != null) {
            sb.append(color(DIM, "   Config: ")).append(cfg.configPath());
        }
        sb.append('\n');
        out.println(sb);
    }

    /**
     * Repaints the live progress panel (in place on interactive terminals).
     */
    public void paint(LiveStats stats) {
        String[] lines = render(stats);
        if (live) {
            if (paintedLines > 0) {
                out.print("\u001B[" + paintedLines + "A");
            }
            for (String line : lines) {
                out.print("\r\u001B[2K");
                out.println(line);
            }
            paintedLines = lines.length;
        } else {
            out.print('\r');
            out.print("\u001B[2K");
            out.print(renderCompact(stats));
            paintedLines = 0;
        }
        out.flush();
    }

    /**
     * Clears the live panel so the terminal ends back on a clean line.
     */
    public void clear() {
        if (!live || paintedLines == 0) {
            out.println();
            paintedLines = 0;
            out.flush();
            return;
        }
        out.print("\u001B[" + paintedLines + "A");
        for (int i = 0; i < paintedLines; i++) {
            out.print("\r\u001B[2K");
            if (i < paintedLines - 1) {
                out.print("\u001B[1B");
            }
        }
        out.println();
        paintedLines = 0;
        out.flush();
    }

    public void printSummary(LoadTestReport report) {
        double rate = report.successRate();
        out.println();
        if (rate >= 99.5) {
            out.println(color(BOLD + GREEN, "\u2714 GREEN \u2014 target met within SLA"));
        } else if (rate >= 95) {
            out.println(color(BOLD + YELLOW, "\u26A0 YELLOW \u2014 tolerable degradation (success \u2265 95%)"));
        } else {
            out.println(color(BOLD + RED, "\u2716 RED \u2014 critical failure (success < 95%)"));
        }
        out.println(color(DIM, "  " + report.total() + " iterations \u00B7 " + report.users()
                + " concurrent users \u00B7 " + formatDuration(report.elapsedMs())));

        out.println();
        out.println(color(BOLD + CYAN, "  Summary"));
        out.println(color(DIM, "    Iterations    ") + pad(report.total()) + color(DIM, "   (" + fmt(report.iterationsPerSec()) + " /s)"));
        out.println(color(DIM, "    Succeeded     ") + pad(report.succeeded()) + color(GREEN, "  \u2714 " + fmt(rate) + "%"));
        out.println(color(DIM, "    Failed        ") + pad(report.failed()) + color(report.failed() > 0 ? RED : DIM, "  \u2716 " + fmt(100 - rate) + "%"));
        out.println(color(DIM, "    HTTP requests ") + pad(report.totalRequests()) + color(DIM, "   (" + fmt(report.requestsPerSec()) + " /s)"));

        out.println();
        out.println(color(BOLD + CYAN, "  Latency (ms)"));
        out.println(color(DIM, "    avg  ") + paintLatency(report.avgLatencyMs()) + color(DIM, "  p50  ") + paintLatency(report.p50Ms())
                + color(DIM, "  p90  ") + paintLatency(report.p90Ms()));
        out.println(color(DIM, "    p95  ") + paintLatency(report.p95Ms()) + color(DIM, "  p99  ") + paintLatency(report.p99Ms())
                + color(DIM, "  max  ") + paintLatency(report.maxLatencyMs()));

        if (report.failed() > 0) {
            out.println();
            out.println(color(BOLD + RED, "  Failures"));
            showFailures(report, 10);
        }
        out.println();
    }

    private String[] render(LiveStats s) {
        String title = color(BOLD + CYAN, "\u26A1 BBRun Load Test") + color(DIM, "   " + targetText(s));
        String bar = color(progressColor(s), progressBar(s) + " " + fmt(s.successRate()) + "% success");
        String counts = color(GREEN, "\u2714 " + pad(s.succeeded())) + "  "
                + color(s.failed() > 0 ? RED : DIM, "\u2716 " + pad(s.failed()))
                + color(DIM, "   " + fmt(s.iterationsPerSec()) + " iter/s \u00B7 " + fmt(s.requestsPerSec()) + " req/s");
        String latency = color(DIM, "Latency  ") + dimLatency("avg", s.avgLatencyMs())
                + dimLatency("p50", s.p50Ms()) + dimLatency("p95", s.p95Ms()) + dimLatency("p99", s.p99Ms());
        return new String[]{title, bar, counts, latency};
    }

    private String renderCompact(LiveStats s) {
        StringBuilder sb = new StringBuilder();
        sb.append(color(DIM, "[t=")).append(formatDuration(s.elapsedMs())).append("] ");
        sb.append(color(DIM, targetText(s))).append(" \u00B7 ");
        sb.append(color(progressColor(s), progressPct(s) + " \u2714" + s.succeeded() + " \u2716" + s.failed()));
        sb.append(color(DIM, " \u00B7 ")).append(fmt(s.iterationsPerSec())).append(" iter/s");
        sb.append(color(DIM, " \u00B7 avg ")).append(fmt(s.avgLatencyMs())).append("ms");
        sb.append(color(DIM, " \u00B7 p95 ")).append(fmt(s.p95Ms())).append("ms   ");
        return sb.toString();
    }

    private String targetText(LiveStats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("elapsed ").append(formatDuration(s.elapsedMs()));
        if (config != null && config.iterations() > 0) {
            sb.append(" \u00B7 iterations ").append(s.total()).append("/").append(config.iterations());
        }
        if (config != null && config.duration() != null) {
            sb.append(" \u00B7 total ").append(formatDuration(config.duration().toMillis()));
        }
        return sb.toString();
    }

    private double progress(LiveStats s) {
        if (config != null && config.iterations() > 0) {
            return Math.min(1.0, s.total() / (double) config.iterations());
        }
        if (config != null && config.duration() != null) {
            return Math.min(1.0, s.elapsedMs() / (double) config.duration().toMillis());
        }
        return 1.0;
    }

    private String progressPct(LiveStats s) {
        return String.format(Locale.ROOT, "%4.1f%%", progress(s) * 100);
    }

    private String progressBar(LiveStats s) {
        int filled = (int) Math.round(progress(s) * BAR_SEGMENTS);
        return BAR_FILL.repeat(filled) + BAR_EMPTY.repeat(BAR_SEGMENTS - filled);
    }

    private String progressColor(LiveStats s) {
        if (s.successRate() >= 99) return GREEN;
        if (s.successRate() >= 95) return YELLOW;
        return RED;
    }

    private String dimLatency(String label, double ms) {
        return color(DIM, "  " + label + " ") + color(latencyColor(ms), fmt(ms) + "ms");
    }

    private String paintLatency(double ms) {
        return color(latencyColor(ms), fmt(ms) + "ms ");
    }

    private String latencyColor(double ms) {
        if (ms < 200) return GREEN;
        if (ms < 1000) return YELLOW;
        return RED;
    }

    private void showFailures(LoadTestReport report, int max) {
        long shown = 0;
        for (LoadSample sample : report.samples()) {
            if (sample.success() || shown >= max) continue;
            String err = sample.error() != null ? sample.error() : "unknown error";
            if (err.length() > width - 12) err = err.substring(0, Math.max(0, width - 15)) + "...";
            out.println(color(RED, "    \u2716 ") + sample.latencyMs() + "ms  " + err);
            shown++;
        }
        if (report.failed() - shown > 0) {
            out.println(color(DIM, "    ... and " + (report.failed() - shown) + " more failure(s)"));
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        return m + "m" + s + "s";
    }

    private String color(String code, String text) {
        return color ? code + text + RESET : text;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String pad(long value) {
        return String.format(Locale.ROOT, "%6d", value);
    }
}