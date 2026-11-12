package org.bbrun.interpreter;

import org.bbrun.runtime.MockResponseSink;
import org.bbrun.runtime.ResourceContext;
import org.bbrun.runtime.ResponseObject;
import org.bbrun.validation.DrefValidatorBridge;
import org.bbrun.validation.MappingRegistry;

import java.util.*;

public class ExecutionContext {

    private final ResourceContext resourceContext;
    private final DrefValidatorBridge drefBridge;
    private final MappingRegistry mappingRegistry;
    private final Map<String, Object> variables = new HashMap<>();

    private String lastResponseResource;
    private MockResponseSink mockSink;
    private String scriptName;
    private Integer statusCode;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();

    private int passedAssertions = 0;
    private int failedAssertions = 0;
    private int requestCount = 0;
    private final List<String> warnings = new ArrayList<>();
    private final long startTime = System.currentTimeMillis();
    private ScriptOutput output = ScriptOutput.STANDARD;

    public ExecutionContext(ResourceContext resourceContext, DrefValidatorBridge drefBridge) {
        this(resourceContext, drefBridge, null);
    }

    public ExecutionContext(ResourceContext resourceContext, DrefValidatorBridge drefBridge, MappingRegistry mappingRegistry) {
        this.resourceContext = resourceContext;
        this.drefBridge = drefBridge;
        this.mappingRegistry = mappingRegistry != null ? mappingRegistry : new MappingRegistry();
    }

    public void setOutput(ScriptOutput output) {
        this.output = output != null ? output : ScriptOutput.STANDARD;
    }

    public ScriptOutput getOutput() {
        return output;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public ResourceContext getResourceContext() {
        return resourceContext;
    }

    public DrefValidatorBridge getDrefBridge() {
        return drefBridge;
    }

    public MappingRegistry getMappingRegistry() {
        return mappingRegistry;
    }

    public void setMockSink(MockResponseSink sink) {
        this.mockSink = sink;
    }

    public MockResponseSink getMockSink() {
        return mockSink;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public String getLastResponseResource() {
        return lastResponseResource;
    }

    public void setLastResponseResource(String resourceName) {
        this.lastResponseResource = resourceName;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    public Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public void recordPass() {
        passedAssertions++;
    }

    public void recordFail() {
        failedAssertions++;
    }

    public void recordRequest() {
        requestCount++;
    }

    public void recordWarning(String warning) {
        warnings.add(warning);
    }

    public int getPassedAssertions() {
        return passedAssertions;
    }

    public int getFailedAssertions() {
        return failedAssertions;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public long getDurationMs() {
        return System.currentTimeMillis() - startTime;
    }

    // ---- goals, steps, cleanups, includes ----

    private final List<String> goals = new ArrayList<>();
    private final List<StepResult> steps = new ArrayList<>();
    private final Deque<Cleanup> cleanups = new ArrayDeque<>();

    /** A cleanup block with the variables and credentials it was registered with. */
    public record Cleanup(List<org.bbrun.ast.StatementNode> body, Map<String, Object> variables,
                          Map<String, Map<String, String>> credentials) {}
    private final Deque<java.nio.file.Path> scriptDirs = new ArrayDeque<>();

    public void addGoal(String goal) {
        goals.add(goal);
    }

    public List<String> getGoals() {
        return Collections.unmodifiableList(goals);
    }

    public void addStep(StepResult step) {
        steps.add(step);
    }

    public List<StepResult> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Registers a cleanup block; they run last registered first when the script ends, each with the
     * variables and credentials of the moment it was registered.
     */
    public void pushCleanup(List<org.bbrun.ast.StatementNode> body) {
        cleanups.push(new Cleanup(body, new HashMap<>(variables),
                resourceContext != null ? resourceContext.credentials() : Map.of()));
    }

    /** The next cleanup to run, or null when none is left. */
    public Cleanup popCleanup() {
        return cleanups.poll();
    }

    /** Puts the variables and credentials of a cleanup back, for it to run. */
    public void restore(Cleanup cleanup) {
        variables.clear();
        variables.putAll(cleanup.variables());
        if (resourceContext != null) resourceContext.restoreCredentials(cleanup.credentials());
    }

    /** The folder that relative paths (include, file, readText…) resolve against. */
    public java.nio.file.Path getScriptDir() {
        java.nio.file.Path dir = scriptDirs.peek();
        return dir != null ? dir : java.nio.file.Path.of("").toAbsolutePath();
    }

    public void pushScriptDir(java.nio.file.Path dir) {
        scriptDirs.push(dir);
    }

    public void popScriptDir() {
        scriptDirs.poll();
    }
}
