package org.bbrun.cli;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.bbrun.interpreter.ExecutionResult;

import org.bbrun.interpreter.StepResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ConsoleReporter {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";

    private boolean colorEnabled = true;
    private boolean verbose = false;
    private boolean quiet = false;

    public void setColorEnabled(boolean enabled) {
        this.colorEnabled = enabled;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public void printHeader(Path script, String env, Path config) {
        if (quiet) return;
        System.out.println(color(BOLD + CYAN, "⚡ BBRun Script Runner") + color(DIM, " (Environment: " + env + ")"));
        System.out.println(color(DIM, "   Script: ") + script);
        if (config != null) {
            System.out.println(color(DIM, "   Config: ") + config);
        }
        System.out.println();
    }

    public void printResult(ExecutionResult result) {
        if (quiet && result.isSuccess()) return;

        System.out.println();
        if (result.isSuccess()) {
            System.out.println(color(BOLD + GREEN, "✔ SUCCESS ") + color(DIM, "(" + result.durationMs() + "ms)"));
        } else {
            System.out.println(color(BOLD + RED, "✖ FAILED ") + color(DIM, "(" + result.durationMs() + "ms)"));
            if (result.errorMessage() != null) {
                System.out.println(color(RED, "  " + result.errorMessage()));
            }
        }

        System.out.println(
                color(DIM, "  Assertions: ") +
                color(GREEN, String.valueOf(result.passedAssertions())) + " passed, " +
                color(result.failedAssertions() > 0 ? RED : DIM, String.valueOf(result.failedAssertions())) + " failed"
        );
        System.out.println(color(DIM, "  Requests:   ") + result.requestCount());
        if (!result.steps().isEmpty()) {
            System.out.println(color(DIM, "  Steps:"));
            for (StepResult step : result.steps()) {
                System.out.println((step.passed() ? color(GREEN, "    ✔ ") : color(RED, "    ✖ ")) + step.name()
                        + color(DIM, " (" + step.durationMs() + "ms)"));
            }
        }

        if (!result.warnings().isEmpty()) {
            System.out.println(color(YELLOW, "  Warnings (" + result.warnings().size() + "):"));
            for (String w : result.warnings()) {
                System.out.println(color(YELLOW, "    ⚠ " + w));
            }
        }
        System.out.println();
    }

    /** A line the script sends while it runs: print (INFO), warn (WARN), goal (GOAL), step (STEP). */
    public void printLine(String level, String message) {
        if (quiet && !"WARN".equals(level)) return;
        switch (level) {
            // The result is printed once the script ends; calls only when verbose
            case "DONE", "ERROR" -> { }
            case "CALL" -> { if (verbose) System.out.println(color(DIM, "  call  " + message)); }
            case "GOAL" -> System.out.println(color(DIM, "  goal  ") + color(BOLD, message));
            case "STEP" -> System.out.println(color(CYAN, "  step  ") + message);
            case "WARN" -> System.out.println(color(YELLOW, "  ⚠ " + message));
            default -> System.out.println(color(DIM, "        ") + message);
        }
    }

    /** Where the script's log file is. */
    public void printLogPath(Path path) {
        if (quiet) return;
        System.out.println(color(DIM, "  log   " + path));
    }

    public void printSuiteHeader(int count, String env, Path config) {
        if (quiet) return;
        System.out.println(color(BOLD + CYAN, "⚡ BBRun") + color(DIM, " " + count + " scripts (Environment: " + env + ")"));
        if (config != null) {
            System.out.println(color(DIM, "   Config: ") + config);
        }
        System.out.println();
    }

    public void printScriptStart(Path script) {
        if (quiet) return;
        System.out.println(color(BOLD, "▶ " + script));
    }

    public void printScriptResult(ExecutionResult result) {
        if (quiet && result.isSuccess()) return;
        if (result.isSuccess()) {
            System.out.println(color(GREEN, "  ✔ passed ") + color(DIM, "(" + result.durationMs() + "ms, "
                    + result.passedAssertions() + " assertions, " + result.requestCount() + " requests)"));
        } else {
            System.out.println(color(RED, "  ✖ failed ") + color(DIM, "(" + result.durationMs() + "ms)"));
            if (result.errorMessage() != null) {
                System.out.println(color(RED, "    " + result.errorMessage()));
            }
        }
        for (String w : result.warnings()) {
            System.out.println(color(YELLOW, "    ⚠ " + w));
        }
        System.out.println();
    }

    /** One line per script, then the totals. */
    public void printSummary(List<Map.Entry<Path, ExecutionResult>> results) {
        long passed = results.stream().filter(r -> r.getValue().isSuccess()).count();
        long failed = results.size() - passed;
        System.out.println(color(BOLD, "Summary"));
        for (Map.Entry<Path, ExecutionResult> r : results) {
            ExecutionResult res = r.getValue();
            String mark = res.isSuccess() ? color(GREEN, "  PASS ") : color(RED, "  FAIL ");
            String steps = res.steps().isEmpty() ? "" : color(DIM, "  " + res.steps().stream().filter(StepResult::passed).count()
                    + "/" + res.steps().size() + " steps");
            System.out.println(mark + r.getKey() + steps + color(DIM, "  " + res.durationMs() + "ms"));
            if (!res.isSuccess() && res.errorMessage() != null) {
                System.out.println(color(RED, "         " + firstLine(res.errorMessage())));
            }
        }
        System.out.println();
        String line = passed + " passed, " + failed + " failed, " + results.size() + " scripts";
        System.out.println(failed == 0 ? color(BOLD + GREEN, "✔ " + line) : color(BOLD + RED, "✖ " + line));
    }

    public void printSuiteJson(List<Map.Entry<Path, ExecutionResult>> results) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<Path, ExecutionResult> r : results) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("script", r.getKey().toString());
            item.put("result", resultJson(r.getValue()));
            out.add(item);
        }
        System.out.println(JSON.toJSONString(out, JSONWriter.Feature.PrettyFormat));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    public void printResultJson(ExecutionResult result) {
        System.out.println(JSON.toJSONString(resultJson(result), JSONWriter.Feature.PrettyFormat));
    }

    /**
     * A result as the JSON object {@code --json} prints: its fields by name, in alphabetical
     * order, those without a value left out. Written out field by field, so that it does not
     * depend on how a JSON library reads a record (the native executable has no such access).
     */
    static Map<String, Object> resultJson(ExecutionResult result) {
        Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("durationMs", result.durationMs());
        putIfPresent(json, "errorMessage", result.errorMessage());
        json.put("failedAssertions", result.failedAssertions());
        json.put("goals", result.goals());
        json.put("isSuccess", result.isSuccess());
        json.put("passedAssertions", result.passedAssertions());
        putIfPresent(json, "reason", result.reason());
        json.put("requestCount", result.requestCount());
        putIfPresent(json, "responseHeaders", result.responseHeaders());
        putIfPresent(json, "statusCode", result.statusCode());
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (StepResult step : result.steps()) {
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("durationMs", step.durationMs());
            putIfPresent(s, "error", step.error());
            s.put("name", step.name());
            s.put("passed", step.passed());
            steps.add(s);
        }
        json.put("steps", steps);
        putIfPresent(json, "value", result.value());
        putIfPresent(json, "warnings", result.warnings());
        return json;
    }

    private static void putIfPresent(Map<String, Object> json, String key, Object value) {
        if (value != null) json.put(key, value);
    }

    public void printError(Throwable t) {
        System.err.println(color(BOLD + RED, "Error: ") + t.getMessage());
        if (verbose) {
            t.printStackTrace(System.err);
        }
    }

    private String color(String code, String text) {
        if (!colorEnabled) return text;
        return code + text + RESET;
    }
}
