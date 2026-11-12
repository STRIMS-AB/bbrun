package org.bbrun.cli;

import org.bbrun.BBRun;
import org.bbrun.config.*;
import org.bbrun.interpreter.ExecutionResult;
import org.bbrun.load.LoadTestConfig;
import org.bbrun.load.LoadTestReport;
import org.bbrun.load.LoadTestRunner;
import org.bbrun.load.LiveStats;
import dev.dref.DomainModel;
import dev.dref.Property;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

@Command(
        name = "bbrun",
        mixinStandardHelpOptions = true,
        version = "bbrun 1.0.0",
        description = "BBRun - Scripting language for testing & integration with resource abstractions and DRef validation",
        subcommands = {
                Main.RunCommand.class,
                Main.ValidateCommand.class,
                Main.EnvsCommand.class,
                Main.ModelsCommand.class,
                Main.LoadCommand.class
        }
)
public class Main implements Callable<Integer> {

    @Parameters(arity = "0..*", description = "Script files or folders to execute (as 'run')")
    private java.util.List<Path> defaultScripts = new java.util.ArrayList<>();

    @Option(names = { "-e", "--env" }, description = "Active environment (e.g. dev, local, staging)")
    private String environment;

    @Option(names = { "-c", "--config" }, description = "Configuration file path (default: bbrun.yaml or bbrun.json)")
    private Path configPath;

    @Option(names = { "-v", "--verbose" }, description = "Verbose output")
    private boolean verbose;

    @Option(names = { "--no-color" }, description = "Disable colored console output")
    private boolean noColor;

    @Option(names = { "--json" }, description = "Output result as JSON")
    private boolean json;

    @Option(names = { "--log-dir" }, description = "Folder for one log file per script (default: logging.dir of the config; none without it)")
    private Path logDir;

    @Option(names = { "-q", "--quiet" }, description = "Quiet mode (only print errors)")
    private boolean quiet;

    @Override
    public Integer call() {
        if (!defaultScripts.isEmpty()) {
            RunCommand run = new RunCommand();
            run.scripts = defaultScripts;
            run.environment = environment;
            run.configPath = configPath;
            run.verbose = verbose;
            run.noColor = noColor;
            run.json = json;
            run.quiet = quiet;
            run.logDir = logDir;
            return run.call();
        }
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "run", description = "Execute BBRun scripts: files, or folders of .bbrun files (a suite, with a summary)")
    public static class RunCommand implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "Script files (.bbrun) or folders; in a folder, files and folders whose name starts with _ are left out")
        java.util.List<Path> scripts = new java.util.ArrayList<>();

        @Option(names = { "-e", "--env" }, description = "Active environment name")
        String environment;

        @Option(names = { "-c", "--config" }, description = "Configuration file path (default: bbrun.yaml in the current folder, else next to the first script or in a folder above it)")
        Path configPath;

        @Option(names = { "-v", "--verbose" }, description = "Verbose output")
        boolean verbose;

        @Option(names = { "--no-color" }, description = "Disable colored output")
        boolean noColor;

        @Option(names = { "--json" }, description = "Output as JSON")
        boolean json;

        @Option(names = { "-q", "--quiet" }, description = "Quiet output")
        boolean quiet;

        @Option(names = { "--log-dir" }, description = "Folder for one log file per script (default: logging.dir of the config; none without it)")
        Path logDir;

        @Override
        public Integer call() {
            ConsoleReporter reporter = new ConsoleReporter();
            reporter.setColorEnabled(!noColor);
            reporter.setVerbose(verbose);
            reporter.setQuiet(quiet);

            java.util.List<Path> files;
            try {
                files = expand(scripts);
            } catch (IllegalArgumentException | java.io.IOException e) {
                reporter.printError(e);
                return 1;
            }
            if (files.isEmpty()) {
                reporter.printError(new IllegalArgumentException("No .bbrun script in " + scripts));
                return 1;
            }

            Path resolvedConfig = findConfig(configPath, files.get(0));
            BBRunConfig cfg = ConfigLoader.loadOrDefaults(resolvedConfig);
            String targetEnv = (environment != null && !environment.isBlank())
                    ? environment
                    : cfg.defaultEnvironment();

            BBRun bbrun;
            try {
                bbrun = BBRun.builder()
                        .withConfigFile(resolvedConfig)
                        .withEnvironment(targetEnv)
                        .build();
            } catch (Exception e) {
                reporter.printError(e);
                return 1;
            }

            boolean suite = files.size() > 1 || scripts.stream().anyMatch(Files::isDirectory);
            java.util.List<Map.Entry<Path, ExecutionResult>> results = new java.util.ArrayList<>();
            if (!json && !suite) {
                reporter.printHeader(files.get(0), targetEnv != null ? targetEnv : "default", resolvedConfig);
            } else if (!json) {
                reporter.printSuiteHeader(files.size(), targetEnv != null ? targetEnv : "default", resolvedConfig);
            }
            Path logs = logDir != null ? logDir : cfg.logDir();
            for (Path file : files) {
                if (!json && suite) reporter.printScriptStart(file);
                ExecutionResult result;
                org.bbrun.interpreter.ScriptOutput console = json ? org.bbrun.interpreter.ScriptOutput.NONE : reporter::printLine;
                org.bbrun.interpreter.FileLog log = null;
                try {
                    if (logs != null) log = org.bbrun.interpreter.FileLog.in(logs, String.valueOf(file.getFileName()));
                    result = bbrun.execute(file, log != null ? org.bbrun.interpreter.ScriptOutput.both(console, log) : console);
                } catch (Exception e) {
                    result = ExecutionResult.failure(0, 0, 0, java.util.List.of(), 0, e.getMessage());
                } finally {
                    if (log != null) {
                        try { log.close(); } catch (java.io.IOException ignored) { }
                    }
                }
                results.add(Map.entry(file, result));
                if (!json) {
                    if (suite) reporter.printScriptResult(result);
                    else reporter.printResult(result);
                    if (log != null) reporter.printLogPath(log.path());
                }
            }

            if (json) {
                if (suite) reporter.printSuiteJson(results);
                else reporter.printResultJson(results.get(0).getValue());
            } else if (suite) {
                reporter.printSummary(results);
            }
            return results.stream().allMatch(r -> r.getValue().isSuccess()) ? 0 : 1;
        }

        /**
         * Files as given, folders as their .bbrun files in name order, leaving out names
         * starting with _ (included scripts) and folders named mocks (the scripts of mock
         * routes, which only answer a request).
         */
        static java.util.List<Path> expand(java.util.List<Path> paths) throws java.io.IOException {
            java.util.List<Path> out = new java.util.ArrayList<>();
            for (Path p : paths) {
                if (Files.isDirectory(p)) {
                    try (var walk = Files.walk(p)) {
                        walk.filter(Files::isRegularFile)
                                .filter(f -> f.getFileName().toString().endsWith(".bbrun"))
                                .filter(f -> {
                                    Path inFolder = p.relativize(f);
                                    for (Path part : inFolder) {
                                        if (part.toString().startsWith("_")) return false;
                                    }
                                    Path folders = inFolder.getParent();
                                    if (folders != null) {
                                        for (Path folder : folders) {
                                            if (folder.toString().equals("mocks")) return false;
                                        }
                                    }
                                    return true;
                                })
                                .sorted()
                                .forEach(out::add);
                    }
                } else if (Files.isRegularFile(p)) {
                    out.add(p);
                } else {
                    throw new IllegalArgumentException("Script file not found: " + p);
                }
            }
            return out;
        }
    }

    @Command(name = "validate", description = "Validate script syntax and structure without executing")
    public static class ValidateCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Script file to validate")
        Path script;

        @Override
        public Integer call() {
            if (script == null || !Files.exists(script)) {
                System.err.println("Error: Script file not found: " + script);
                return 1;
            }
            try {
                BBRun.builder().build().validateSyntax(script);
                System.out.println("✔ Syntax valid: " + script);
                return 0;
            } catch (Exception e) {
                System.err.println("✖ Syntax error in " + script + ": " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "envs", description = "List configured environments and resource capabilities")
    public static class EnvsCommand implements Callable<Integer> {
        @Option(names = { "-c", "--config" }, description = "Configuration file path")
        Path configPath;

        @Option(names = { "--no-color" }, description = "Disable colored output (accepted for consistency)")
        boolean noColor;

        @Override
        public Integer call() {
            Path resolved = findConfig(configPath);
            BBRunConfig cfg = ConfigLoader.loadOrDefaults(resolved);

            System.out.println("BBRun Configured Environments (" + cfg.environments().size() + "):");
            for (EnvironmentConfig env : cfg.environments().values()) {
                boolean isDefault = env.name().equals(cfg.defaultEnvironment());
                System.out.println("\n  • " + env.name() + (isDefault ? " [DEFAULT]" : ""));
                for (ResourceDefinition res : env.resources().values()) {
                    String baseUrl = res.baseUrl() != null ? " -> " + res.baseUrl() : "";
                    System.out.println("      - " + res.name() + " (" + res.resolvedType() + ")" + baseUrl);
                    if (res.auth() != null && !"none".equals(res.auth().type())) {
                        System.out.println("        auth: " + res.auth().type());
                    }
                }
            }
            System.out.println();
            return 0;
        }
    }

    @Command(name = "models", description = "List available DRef domain models and validation properties")
    public static class ModelsCommand implements Callable<Integer> {
        @Option(names = { "-c", "--config" }, description = "Configuration file path")
        Path configPath;

        @Option(names = { "--no-color" }, description = "Disable colored output (accepted for consistency)")
        boolean noColor;

        @Override
        public Integer call() {
            Path resolved = findConfig(configPath);
            BBRunConfig cfg = ConfigLoader.loadOrDefaults(resolved);
            BBRun bbrun = BBRun.builder().withConfig(cfg).build();

            Map<String, DomainModel> models = bbrun.getDrefRegistry().getAllModels();
            System.out.println("DRef Domain Models (" + models.size() + "):");
            if (models.isEmpty()) {
                System.out.println("  (No domain model document found: set 'domain' in bbrun.yaml or provide domain.json next to it)");
            } else {
                for (DomainModel model : models.values()) {
                    System.out.println("\n  • " + model.name() + " (" + model.classify() + ", authority=" + model.authority() + ", ttl=" + model.ttl() + ")");
                    for (Property prop : model.properties()) {
                        String req = prop.required() ? " [REQUIRED]" : "";
                        String rCount = !prop.rules().isEmpty() ? " (" + prop.rules().size() + " rules)" : "";
                        System.out.println("      - " + prop.name() + ": " + prop.type() + req + rCount);
                    }
                }
            }
            System.out.println();
            return 0;
        }
    }

    @Command(name = "load", description = "Run a script as a load test with concurrent users, live colored progress and a latency/success summary")
    public static class LoadCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Script file to load test (.bbrun)")
        Path script;

        @Option(names = { "-c", "--config" }, description = "Configuration file path")
        Path configPath;

        @Option(names = { "-e", "--env" }, description = "Active environment name")
        String environment;

        @Option(names = { "-u", "--users" }, defaultValue = "1", description = "Number of concurrent virtual users (default: 1)")
        int users;

        @Option(names = { "-i", "--iterations" }, description = "Total script iterations to run (stops the test when reached)")
        int iterations;

        @Option(names = { "-d", "--duration" }, description = "Run duration, e.g. 30s, 5m, 1h (stops the test when elapsed)")
        String duration;

        @Option(names = { "-r", "--ramp-up" }, description = "Time to ramp up to full concurrency, e.g. 5s (default: start all at once)")
        String rampUp;

        @Option(names = { "--no-color" }, description = "Disable colored output")
        boolean noColor;

        @Option(names = { "-v", "--verbose" }, description = "Verbose output")
        boolean verbose;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            LoadTestReporter reporter = LoadTestReporter.forTerminal(!noColor);

            if (script == null || !Files.exists(script)) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Script file not found: " + script);
            }
            if (users < 1) {
                throw new CommandLine.ParameterException(spec.commandLine(), "--users must be >= 1");
            }
            Duration dur = duration != null ? parseDuration(spec, "--duration", duration) : null;
            Duration ramp = rampUp != null ? parseDuration(spec, "--ramp-up", rampUp) : null;

            LoadTestConfig config;
            try {
                config = LoadTestConfig.builder()
                        .script(script)
                        .users(users)
                        .iterations(iterations)
                        .duration(dur)
                        .rampUp(ramp)
                        .configPath(findConfig(configPath, script))
                        .environment(environment)
                        .build();
            } catch (IllegalArgumentException e) {
                throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
            }

            // As for a run: the config next to the script (or above it) when the current folder has none
            Path resolvedConfig = findConfig(configPath, script);
            String targetEnv = (environment != null && !environment.isBlank())
                    ? environment
                    : (resolvedConfig != null ? ConfigLoader.loadOrDefaults(resolvedConfig).defaultEnvironment() : null);

            BBRun bbrun = BBRun.builder()
                    .withConfigFile(resolvedConfig)
                    .withEnvironment(targetEnv)
                    .build();

            LoadTestRunner runner = new LoadTestRunner(bbrun, config);
            AtomicReference<LoadTestReport> reportRef = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            reporter.printHeader(config);
            Thread worker = new Thread(() -> {
                try {
                    reportRef.set(runner.run());
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "load-test-worker");
            worker.start();

            long lastRefresh = 0;
            while (worker.isAlive()) {
                long now = System.currentTimeMillis();
                if (now - lastRefresh >= 150) {
                    reporter.paint(runner.snapshot());
                    lastRefresh = now;
                }
                Thread.sleep(50);
            }
            reporter.clear();

            Throwable t = failure.get();
            if (t != null) {
                throw new RuntimeException("Load test crashed: " + t.getMessage(), t);
            }

            LoadTestReport report = reportRef.get();
            reporter.printSummary(report);
            return report.failed() > report.total() * 0.05 ? 1 : 0;
        }

        private static Duration parseDuration(CommandSpec spec, String flag, String value) {
            String v = value.trim().toLowerCase();
            try {
                if (v.endsWith("ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
                if (v.endsWith("s")) return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
                if (v.endsWith("m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
                if (v.endsWith("h")) return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
                return Duration.ofSeconds(Long.parseLong(v));
            } catch (NumberFormatException e) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "Invalid " + flag + " '" + value + "'. Use a number of seconds or a unit suffix (ms, s, m, h), e.g. 30s or 5m.");
            }
        }
    }

    private static Path findConfig(Path explicit) {
        return findConfig(explicit, null);
    }

    /** The explicit config, else bbrun.yaml/.yml/.json in the current folder, else next to the script or in a folder above it. */
    private static Path findConfig(Path explicit, Path script) {
        if (explicit != null && Files.exists(explicit)) return explicit;
        Path inCwd = configIn(Path.of(""));
        if (inCwd != null) return inCwd;
        if (script != null) {
            for (Path dir = script.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
                Path found = configIn(dir);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Path configIn(Path dir) {
        for (String name : new String[] { "bbrun.yaml", "bbrun.yml", "bbrun.json" }) {
            Path p = dir.resolve(name);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
