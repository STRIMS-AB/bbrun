package org.bbrun.cli;

import org.bbrun.BBRun;
import org.bbrun.ExecutionResult;
import org.bbrun.interpreter.ExecutionHandle;
import org.bbrun.interpreter.ExecutionProgress;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * BBRun CLI entry point.
 */
@Command(name = "bbrun", mixinStandardHelpOptions = true, version = "bbrun 0.1.0", description = "Execute BBRun API test scripts")
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Script file to execute")
    private Path script;

    @Option(names = { "-v", "--verbose" }, description = "Show detailed output")
    private boolean verbose;

    @Option(names = { "--no-color" }, description = "Disable colored output")
    private boolean noColor;

    @Option(names = { "--json" }, description = "Output results as JSON")
    private boolean json;

    @Option(names = { "-q", "--quiet" }, description = "Only show errors")
    private boolean quiet;

    @Option(names = { "--threads" }, description = "Number of parallel threads", defaultValue = "1")
    private int threads;

    @Option(names = { "--duration" }, description = "Duration for load testing (e.g., 1m, 30s)")
    private String duration;

    @Option(names = { "--rps" }, description = "Requests per second limit")
    private Integer rps;

    private final ConsoleReporter reporter;

    public Main() {
        this.reporter = new ConsoleReporter();
    }

    @Override
    public Integer call() {
        reporter.setColorEnabled(!noColor);
        reporter.setVerbose(verbose);
        reporter.setQuiet(quiet);

        if (!json) {
            reporter.printHeader(script.toString());
        }

        BBRun bbrun = new BBRun();
        ExecutionHandle handle = bbrun.execute(script);

        // Poll for progress
        if (!json && !quiet) {
            while (!handle.isComplete()) {
                ExecutionProgress progress = handle.poll();
                reporter.printProgress(progress);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            ExecutionResult result = handle.future().get();

            if (json) {
                reporter.printResultJson(result);
            } else {
                reporter.printResult(result);
            }

            return result.isSuccess() ? 0 : 1;

        } catch (Exception e) {
            reporter.printError(e);
            return 1;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
