package org.bbrun;

import org.bbrun.ast.Statements.ScriptNode;
import org.bbrun.config.BBRunConfig;
import org.bbrun.config.ConfigLoader;
import org.bbrun.config.ConfigurationException;
import org.bbrun.config.DrefConfig;
import org.bbrun.config.EnvironmentConfig;
import org.bbrun.config.MockRoute;
import org.bbrun.config.ResourceAuth;
import org.bbrun.config.ResourceDefinition;
import org.bbrun.interpreter.ExecutionContext;
import org.bbrun.interpreter.ExecutionResult;
import org.bbrun.interpreter.Interpreter;
import org.bbrun.interpreter.ScriptOutput;
import org.bbrun.parser.ScriptParser;
import org.bbrun.runtime.ResourceContext;
import org.bbrun.runtime.ServiceHandler;
import org.bbrun.validation.DrefValidatorBridge;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.MappingRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Embedded engine facade for <b>service integrations</b>: run orchestration
 * scripts inside a Java server, pulling from different resources, transforming
 * and combining into the value used to build a response (or not — flows without
 * a {@code return} are fire-and-forget operations).
 *
 * <p>This is distinct from the {@link BBRun} test-runner facade: the CLI test
 * workflow (mocks, assertion counts, console reporting) stays there, while
 * {@code IntegrationEngine} is the embedding contract. Both share the same
 * scripting language, resource handlers and interpreter.
 *
 * <p>Resources are registered programmatically, and/or imported from a
 * bbrun.yaml file. A {@code return <expr>} statement ends the flow and its
 * value arrives on {@link ExecutionResult#value()}; a flow without return
 * completes normally with a {@code null} value. Scripts can attach response
 * signals with {@code status(code)} and {@code header(name, value)}, which the
 * host reads from {@link ExecutionResult#statusCode()} and
 * {@link ExecutionResult#responseHeaders()}. Without them the host applies its
 * own default mapping (e.g. value → 200, null → 204).
 *
 * <pre>{@code
 * ExecutionResult flow = IntegrationEngine.create()
 *         .definedIn(Path.of("bbrun.yaml"))
 *         .resource(IntegrationEngine.httpRequest("paymentsApi", "https://api.example.com")
 *                 .authBearer(token))
 *         .build()
 *         .execute(Path.of("flows/charge.bbrun"));
 *
 * Object responseBody = flow.value(); // JSON-compatible maps/lists/scalars, not a generated Java DTO
 * }</pre>
 */
public final class IntegrationEngine {

    private final BBRunConfig config;
    private final DrefRegistry drefRegistry;
    private final MappingRegistry mappingRegistry;
    private final Map<String, ServiceHandler> services;

    private IntegrationEngine(BBRunConfig config, DrefRegistry drefRegistry, MappingRegistry mappingRegistry,
                              Map<String, ServiceHandler> services) {
        this.config = config;
        this.services = Map.copyOf(services);
        this.drefRegistry = drefRegistry;
        this.mappingRegistry = mappingRegistry;
        this.mappingRegistry.setDrefRegistry(drefRegistry);
    }

    public static Builder create() {
        return new Builder();
    }

    public static Builder fromConfig(Path configPath) {
        return create().definedIn(configPath);
    }

    /**
     * Programmatic HTTP resource declaration: transport + optional auth; the
     * script targets it by resource name. Resource kinds with test-runner
     * semantics (mocks, file/sql/queue/email locals) are declared via config
     * with {@link Builder#definedIn(Path)}.
     */
    public static HttpResource httpRequest(String name, String baseUrl) {
        return new HttpResource(name, baseUrl);
    }

    /** Builder for an HTTP resource: no mocks, no routing — real transport. */
    public static final class HttpResource {
        private final String name;
        private final String baseUrl;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private ResourceAuth auth = ResourceAuth.none();

        private HttpResource(String name, String baseUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
        }

        public HttpResource header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public HttpResource authBearer(String token) {
            this.auth = ResourceAuth.bearer(token);
            return this;
        }

        public HttpResource authBasic(String username, String password) {
            this.auth = ResourceAuth.basic(username, password);
            return this;
        }

        public HttpResource authApiKey(String headerName, String headerValue) {
            this.auth = ResourceAuth.apiKey(headerName, headerValue);
            return this;
        }

        public HttpResource authOAuth2(String tokenUrl, String clientId, String clientSecret, String scope) {
            this.auth = ResourceAuth.oauth2(tokenUrl, clientId, clientSecret, scope);
            return this;
        }

        ResourceDefinition definition() {
            return new ResourceDefinition(name, "http", baseUrl, headers, auth);
        }
    }

    /** Programmatic mock resource declaration with routed responses. */
    public static MockResource mockResource(String name) {
        return new MockResource(name, "http");
    }

    /** Programmatic mock resource of specific kind (http, sql, queue, email, file). */
    public static MockResource mockResource(String name, String mockKind) {
        return new MockResource(name, mockKind);
    }

    /** Builder for a mock resource with routed responses. */
    public static final class MockResource {
        private final String name;
        private final String mockKind;
        private final List<MockRoute> routes = new ArrayList<>();
        private String mapping;

        private MockResource(String name, String mockKind) {
            this.name = name;
            this.mockKind = mockKind != null ? mockKind : "http";
        }

        public MockResource route(MockRoute route) {
            this.routes.add(route);
            return this;
        }

        public MockResource route(Set<String> methods, String path, Integer status, Object data) {
            return route(new MockRoute(methods, path, status, data, null, null, null, Map.of()));
        }

        public MockResource exprRoute(Set<String> methods, String path, Integer status, String expr) {
            return route(new MockRoute(methods, path, status, null, null, expr, null, Map.of()));
        }

        public MockResource scriptRoute(Set<String> methods, String path, Integer status, String script) {
            return route(new MockRoute(methods, path, status, null, null, null, script, Map.of()));
        }

        public MockResource opRoute(String op, Object data) {
            return route(new MockRoute(Set.of(), null, 200, data, null, null, null, Map.of(), null, op));
        }

        public MockResource opExprRoute(String op, String expr) {
            return route(new MockRoute(Set.of(), null, 200, null, null, expr, null, Map.of(), null, op));
        }

        /** Binds this mock resource to a mapping name a model declares with 'mapsFrom'. */
        public MockResource mapping(String mappingName) {
            this.mapping = mappingName;
            return this;
        }

        public ResourceDefinition definition() {
            return new ResourceDefinition(name, "mock", null, Map.of(), ResourceAuth.none(),
                    mapping, routes, null, null, null, mockKind);
        }
    }

    public ExecutionResult execute(Path scriptPath) throws IOException {
        return execute(scriptPath, Map.of(), Map.of());
    }

    public ExecutionResult execute(String script) {
        return execute(script, Map.of(), Map.of());
    }

    /**
     * Executes one service invocation with JSON-compatible request data and
     * server-supplied context (for example caller identity and correlation ID).
     * These are bound as {@code request} and {@code context}, separately from
     * the interpreter's {@code response}, which describes the last upstream call.
     * Each invocation has its own variables and resource context.
     */
    public ExecutionResult execute(Path scriptPath, Map<String, Object> request,
                                   Map<String, Object> context) throws IOException {
        return run(Files.readString(scriptPath), scriptPath.getFileName().toString(), request, context, ScriptOutput.STANDARD);
    }

    public ExecutionResult execute(String script, Map<String, Object> request,
                                   Map<String, Object> context) {
        return run(script, null, request, context, ScriptOutput.STANDARD);
    }

    /**
     * As {@link #execute(String, Map, Map)}, sending the script's {@code print} and
     * {@code warn} lines to {@code output} as they happen. Interrupting the calling
     * thread stops the script before its next statement.
     */
    public ExecutionResult execute(String script, Map<String, Object> request,
                                   Map<String, Object> context, ScriptOutput output) {
        return run(script, null, request, context, output);
    }

    private ExecutionResult run(String script, String scriptName, Map<String, Object> request,
                                Map<String, Object> invocationContext, ScriptOutput output) {
        ScriptNode scriptNode = ScriptParser.parse(script);
        DrefValidatorBridge bridge = new DrefValidatorBridge(drefRegistry);
        try (ResourceContext resCtx = new ResourceContext(config.getEnvironment(null), config.baseDir())) {
            services.forEach(resCtx::registerService);
            ExecutionContext ctx = new ExecutionContext(resCtx, bridge, mappingRegistry);
            ctx.setScriptName(scriptName);
            ctx.setOutput(output);
            ctx.setVariable("request", new LinkedHashMap<>(request));
            ctx.setVariable("context", new LinkedHashMap<>(invocationContext));
            return new Interpreter(ctx).execute(scriptNode);
        }
    }

    /** @return the engine's as-run configuration (useful for introspection/tests) */
    public BBRunConfig getConfig() {
        return config;
    }

    public DrefRegistry getDrefRegistry() {
        return drefRegistry;
    }

    public MappingRegistry getMappingRegistry() {
        return mappingRegistry;
    }

    public static final class Builder {
        private BBRunConfig imported;
        private final Map<String, ResourceDefinition> resources = new LinkedHashMap<>();
        private DrefRegistry drefRegistry = new DrefRegistry();
        private MappingRegistry mappingRegistry = new MappingRegistry();
        private final Map<String, ServiceHandler> services = new LinkedHashMap<>();

        /**
         * Register a service the host provides in Java, called from scripts as
         * {@code name.operation(args)}, e.g. a platform API.
         */
        public Builder service(String name, ServiceHandler handler) {
            if (resources.containsKey(name) || services.containsKey(name)) {
                throw new ConfigurationException("Duplicate resource name: '" + name + "'");
            }
            services.put(name, handler);
            return this;
        }

        /** Import resources + domain/mappings conventions from a bbrun.yaml file. */
        public Builder definedIn(Path configPath) {
            this.imported = ConfigLoader.load(configPath);
            return this;
        }

        /** Register a programmatic HTTP resource. */
        public Builder resource(HttpResource resource) {
            return resource(resource.definition());
        }

        /** Register a programmatic mock resource with configured routes. */
        public Builder resource(MockResource mockResource) {
            return resource(mockResource.definition());
        }

        /** Register any programmatic resource definition. */
        public Builder resource(ResourceDefinition def) {
            if (resources.containsKey(def.name())) {
                throw new ConfigurationException("Duplicate resource name: '" + def.name() + "'");
            }
            resources.put(def.name(), def);
            return this;
        }

        /** Register the DRef domain document used by `validate`/`matches`. */
        public Builder domain(Path domainFile) {
            drefRegistry.loadFromFile(domainFile);
            return this;
        }

        /** Register the DRef domain document from JSON string. */
        public Builder domainJson(String json) {
            if (json != null && !json.isBlank()) {
                drefRegistry.parseAndRegister(json);
            }
            return this;
        }

        public IntegrationEngine build() {
            BBRunConfig cfg = mergedConfig();

            if (imported != null && imported.dref() != null) {
                Path domainFile = resolve(imported.dref().domain(), "domain.json", cfg);
                if (Files.exists(domainFile)) {
                    drefRegistry.loadFromFile(domainFile);
                }
            }

            mappingRegistry.setDrefRegistry(drefRegistry);
            mappingRegistry.registerInlineMappings();
            bindResourceMappings(cfg);

            return new IntegrationEngine(cfg, drefRegistry, mappingRegistry, services);
        }

        /** Binds each resource to the mapping it names, now that mappings are registered. */
        private void bindResourceMappings(BBRunConfig cfg) {
            if (cfg == null || cfg.environments() == null) return;
            for (EnvironmentConfig env : cfg.environments().values()) {
                if (env.resources() == null) continue;
                for (ResourceDefinition resource : env.resources().values()) {
                    mappingRegistry.bindResource(resource.name(), resource.mapping());
                }
            }
        }

        /** All config-relative paths resolve against the imported file's directory. */
        private Path resolve(String relative, String fallback, BBRunConfig cfg) {
            String value = relative != null ? relative : fallback;
            Path p = Path.of(value);
            if (p.isAbsolute() || cfg.basePath() == null) {
                return p;
            }
            return cfg.basePath().resolve(p).normalize();
        }

        private BBRunConfig mergedConfig() {
            if (imported == null) {
                EnvironmentConfig env = new EnvironmentConfig("default", resources);
                return new BBRunConfig("1.0", "default", Map.of("default", env), DrefConfig.defaults(), null);
            }
            EnvironmentConfig importedEnv = imported.getEnvironment(null);
            Map<String, ResourceDefinition> merged = new LinkedHashMap<>(importedEnv.resources());
            resources.forEach((name, override) ->
                    merged.put(name, withInheritedBinding(merged.get(name), override)));
            EnvironmentConfig env = new EnvironmentConfig(importedEnv.name(), merged);
            return new BBRunConfig(imported.version(), env.name(), Map.of(env.name(), env),
                    imported.dref(), imported.basePath());
        }

        /**
         * A programmatic override (a new baseUrl, say) says nothing about which
         * mapping the resource speaks, so it keeps the binding its imported config
         * declared. Only an override that names its own mapping replaces it.
         */
        private static ResourceDefinition withInheritedBinding(ResourceDefinition fromConfig,
                                                              ResourceDefinition override) {
            if (fromConfig == null || fromConfig.mapping() == null
                    || override.mapping() != null) {
                return override;
            }
            return new ResourceDefinition(override.name(), override.type(), override.baseUrl(),
                    override.headers(), override.auth(), fromConfig.mapping(), override.routes(),
                    override.dir(), override.seed(), override.from(), override.mock());
        }
    }
}
