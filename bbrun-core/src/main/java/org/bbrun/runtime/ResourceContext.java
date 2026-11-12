package org.bbrun.runtime;

import org.bbrun.config.ConfigurationException;
import org.bbrun.config.EnvironmentConfig;
import org.bbrun.config.ResourceDefinition;
import dev.iconn.api.Resource;
import dev.iconn.api.ResourceManager;
import dev.iconn.core.DefaultResourceManager;

import java.nio.file.Path;
import java.util.*;

public class ResourceContext implements AutoCloseable {

    private final EnvironmentConfig environment;
    private final ResourceManager resourceManager;
    private final Map<String, ResourceHandler> handlers = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> credentials = new HashMap<>();

    public ResourceContext(EnvironmentConfig environment) {
        this(environment, null);
    }

    public ResourceContext(EnvironmentConfig environment, Path baseDir) {
        this.environment = environment;

        Map<String, dev.iconn.api.ResourceDefinition> iconnDefs = new LinkedHashMap<>();
        if (environment != null && environment.resources() != null) {
            environment.resources().forEach((name, def) -> {
                String type = def.resolvedType();
                if ("mock".equals(type)) {
                    ResourceHandler handler = def.mockKind().equals("http")
                            ? new MockResourceHandler(def)
                            : new RoutedMockResourceHandler(def);
                    if (handler instanceof ResourceContextAware aware) {
                        aware.attachResourceContext(this);
                    }
                    handlers.put(name, handler);
                } else {
                    iconnDefs.put(name, def.toIconnDefinition(baseDir));
                }
            });
        }

        DefaultResourceManager defaultMgr = new DefaultResourceManager(iconnDefs);
        if (!iconnDefs.isEmpty()) {
            defaultMgr.start();
        }
        this.resourceManager = defaultMgr;

        if (environment != null && environment.resources() != null) {
            environment.resources().forEach((name, def) -> {
                if (!handlers.containsKey(name)) {
                    Resource res = defaultMgr.get(name);
                    ResourceHandler handler = new IconnResourceHandler(def, res);
                    handlers.put(name, handler);
                }
            });
        }
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public EnvironmentConfig getEnvironment() {
        return environment;
    }

    public ResourceHandler getHandler(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new ConfigurationException(
                    "Resource calls must target an explicit resource: use '<resource>.<op>(...)' or '<verb> <resource>.\"/path\"'");
        }
        ResourceHandler handler = handlers.get(resourceName);
        if (handler == null) {
            throw new ConfigurationException("Unknown resource '" + resourceName + "'. Available resources: " + handlers.keySet());
        }
        return handler;
    }

    public ResponseObject invoke(String resourceName, String operation, List<Object> args, Map<String, String> headers) {
        ResourceHandler handler = getHandler(resourceName);
        Map<String, String> sent = new LinkedHashMap<>();
        if (headers != null) sent.putAll(headers);
        sent.putAll(credentials.getOrDefault(resourceName, Map.of()));
        List<Object> plain = new ArrayList<>(args.size());
        for (Object arg : args) {
            if (arg instanceof RequestHeaders h) {
                sent.putAll(h.values());
            } else if (arg instanceof RequestBody b) {
                if (b.contentType() != null) sent.put("Content-Type", b.contentType());
                plain.add(b.content());
            } else {
                plain.add(arg);
            }
        }
        return handler.execute(operation, plain, sent.isEmpty() ? null : sent);
    }

    /**
     * Sends these credentials with every later call to the resource, in place of the configured
     * ones: {@code {type: bearer, token}}, {@code {type: apikey, headerName, headerValue}},
     * {@code {type: basic, username, password}}, {@code {type: headers, headers: {...}}}, or
     * {@code {type: none}} to stop sending what an earlier call set.
     */
    public void authenticate(String resourceName, Map<?, ?> auth) {
        getHandler(resourceName);
        String type = auth == null || auth.get("type") == null ? "none" : String.valueOf(auth.get("type")).toLowerCase(Locale.ROOT);
        Map<String, String> headers = new LinkedHashMap<>();
        switch (type) {
            case "none" -> { }
            case "bearer" -> headers.put("Authorization", "Bearer " + required(auth, "token", type));
            case "apikey", "api_key" -> headers.put(
                    auth.get("headerName") != null ? String.valueOf(auth.get("headerName")) : "X-API-Key",
                    required(auth, "headerValue", type));
            case "basic" -> headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (required(auth, "username", type) + ":" + required(auth, "password", type))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            case "headers" -> {
                if (!(auth.get("headers") instanceof Map<?, ?> m)) {
                    throw new ConfigurationException("authenticate: type headers needs a 'headers' object");
                }
                m.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
            default -> throw new ConfigurationException("authenticate: unknown type '" + type
                    + "'; use bearer, apikey, basic, headers or none");
        }
        credentials.put(resourceName, headers);
    }

    /** The credentials authenticate(...) set, per resource: a copy. */
    public Map<String, Map<String, String>> credentials() {
        return new HashMap<>(credentials);
    }

    /** Puts back credentials taken with {@link #credentials()}. */
    public void restoreCredentials(Map<String, Map<String, String>> saved) {
        credentials.clear();
        credentials.putAll(saved);
    }

    private static String required(Map<?, ?> auth, String field, String type) {
        Object value = auth.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ConfigurationException("authenticate: type " + type + " needs '" + field + "'");
        }
        return String.valueOf(value);
    }

    /** Adds a service the host provides in Java, reachable as {@code name}. */
    public void registerService(String name, ServiceHandler service) {
        if (handlers.containsKey(name)) {
            throw new ConfigurationException("Duplicate resource name: '" + name + "'");
        }
        handlers.put(name, new ServiceResourceHandler(name, service));
    }

    public boolean hasHandler(String resourceName) {
        return resourceName != null && handlers.containsKey(resourceName);
    }

    public Map<String, ResourceHandler> getHandlers() {
        return Map.copyOf(handlers);
    }

    @Override
    public void close() {
        if (resourceManager != null) {
            resourceManager.close();
        }
    }
}
