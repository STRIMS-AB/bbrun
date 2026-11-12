package org.bbrun;

import org.bbrun.ast.Statements.ScriptNode;
import org.bbrun.config.*;
import org.bbrun.interpreter.ExecutionContext;
import org.bbrun.interpreter.ExecutionResult;
import org.bbrun.interpreter.Interpreter;
import org.bbrun.parser.ScriptParser;
import org.bbrun.runtime.ResourceContext;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.DrefValidatorBridge;
import org.bbrun.validation.MappingRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BBRun {

    private final BBRunConfig config;
    private final String environmentName;
    private final DrefRegistry drefRegistry;
    private final MappingRegistry mappingRegistry;

    public BBRun(BBRunConfig config, String environmentName, DrefRegistry drefRegistry, MappingRegistry mappingRegistry) {
        this.config = config != null ? config : ConfigLoader.emptyConfig();
        this.environmentName = environmentName;
        this.drefRegistry = drefRegistry != null ? drefRegistry : new DrefRegistry();
        this.mappingRegistry = mappingRegistry != null ? mappingRegistry : new MappingRegistry();
        this.mappingRegistry.setDrefRegistry(this.drefRegistry);
    }

    public static Builder builder() {
        return new Builder();
    }

    public ExecutionResult execute(Path scriptPath) throws IOException {
        return execute(scriptPath, null);
    }

    /**
     * Runs a script file, sending its print, warn, goal and step lines to {@code output}
     * (standard output when null). Relative paths in the script (include, file, readText…)
     * resolve against the script's folder.
     */
    public ExecutionResult execute(Path scriptPath, org.bbrun.interpreter.ScriptOutput output) throws IOException {
        String content = Files.readString(scriptPath);
        return execute(content, String.valueOf(scriptPath.getFileName()),
                scriptPath.toAbsolutePath().getParent(), output);
    }

    public ExecutionResult execute(String scriptContent) {
        return execute(scriptContent, null, null, null);
    }

    private ExecutionResult execute(String scriptContent, String scriptName, Path scriptDir,
                                    org.bbrun.interpreter.ScriptOutput output) {
        ScriptNode script = ScriptParser.parse(scriptContent);
        EnvironmentConfig env = config.getEnvironment(environmentName);
        ResourceContext resCtx = new ResourceContext(env, config.baseDir());
        DrefValidatorBridge bridge = new DrefValidatorBridge(drefRegistry);

        ExecutionContext ctx = new ExecutionContext(resCtx, bridge, mappingRegistry);
        ctx.setScriptName(scriptName);
        if (scriptDir != null) ctx.pushScriptDir(scriptDir);
        if (output != null) ctx.setOutput(output);
        try {
            return new Interpreter(ctx).execute(script);
        } finally {
            resCtx.close();
        }
    }

    public void validateSyntax(Path scriptPath) throws IOException {
        String content = Files.readString(scriptPath);
        ScriptParser.parse(content);
    }

    public BBRunConfig getConfig() {
        return config;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public DrefRegistry getDrefRegistry() {
        return drefRegistry;
    }

    public MappingRegistry getMappingRegistry() {
        return mappingRegistry;
    }

    public static class Builder {
        private BBRunConfig config;
        private String environmentName;
        private DrefRegistry drefRegistry = new DrefRegistry();
        private MappingRegistry mappingRegistry = new MappingRegistry();

        public Builder withConfig(BBRunConfig config) {
            this.config = config;
            return this;
        }

        public Builder withConfigFile(Path configPath) {
            this.config = ConfigLoader.loadOrDefaults(configPath);
            return this;
        }

        public Builder withEnvironment(String envName) {
            this.environmentName = envName;
            return this;
        }

        public Builder withDrefRegistry(DrefRegistry registry) {
            this.drefRegistry = registry;
            return this;
        }

        public Builder withMappingRegistry(MappingRegistry registry) {
            this.mappingRegistry = registry;
            return this;
        }

        /**
         * Resolves a config-relative path against the config file's directory
         * (falling back to the current working directory when the config was
         * loaded without a file).
         */
        private Path resolve(String configValue, String defaultValue) {
            String value = configValue != null ? configValue : defaultValue;
            Path base = config != null && config.basePath() != null ? config.basePath() : Path.of("");
            Path p = Path.of(value);
            return p.isAbsolute() || base == null ? p : base.resolve(p).normalize();
        }

        public BBRun build() {
            if (config != null && config.dref() != null) {
                // The domain model document; default "domain.json" next to the config file.
                // Mappings come from the models' own "mapsFrom" entries.
                Path domainFile = resolve(config.dref().domain(), "domain.json");
                if (Files.exists(domainFile)) {
                    drefRegistry.loadFromFile(domainFile);
                }
            }
            mappingRegistry.setDrefRegistry(drefRegistry);
            mappingRegistry.registerInlineMappings();
            bindResourceMappings(config);
            return new BBRun(config, environmentName, drefRegistry, mappingRegistry);
        }

        private void bindResourceMappings(BBRunConfig cfg) {
            if (cfg == null || cfg.environments() == null) return;
            for (EnvironmentConfig env : cfg.environments().values()) {
                if (env.resources() == null) continue;
                for (ResourceDefinition resource : env.resources().values()) {
                    mappingRegistry.bindResource(resource.name(), resource.mapping());
                }
            }
        }
    }
}
