package org.bbrun.interpreter;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Options for script execution.
 */
public class ExecutionOptions {

    private String baseUrl;
    private Duration timeout = Duration.ofSeconds(30);
    private boolean failFast = true;
    private int threads = 1;
    private Duration duration;
    private Integer rps;
    private Map<String, Object> variables = new HashMap<>();
    private Map<String, String> environment = new HashMap<>();
    private boolean verbose = false;

    public ExecutionOptions() {
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ExecutionOptions baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public ExecutionOptions timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public ExecutionOptions failFast(boolean failFast) {
        this.failFast = failFast;
        return this;
    }

    public int getThreads() {
        return threads;
    }

    public ExecutionOptions threads(int threads) {
        this.threads = threads;
        return this;
    }

    public Duration getDuration() {
        return duration;
    }

    public ExecutionOptions duration(Duration duration) {
        this.duration = duration;
        return this;
    }

    public Integer getRps() {
        return rps;
    }

    public ExecutionOptions rps(Integer rps) {
        this.rps = rps;
        return this;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public ExecutionOptions variable(String name, Object value) {
        this.variables.put(name, value);
        return this;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public ExecutionOptions env(String name, String value) {
        this.environment.put(name, value);
        return this;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public ExecutionOptions verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }
}
