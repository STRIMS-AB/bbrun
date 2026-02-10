package org.bbrun.interpreter;

import java.util.HashMap;
import java.util.Map;

/**
 * Execution context holding variables, auth state, and configuration.
 */
public class Context {

    private final ExecutionOptions options;
    private final Map<String, Object> variables = new HashMap<>();
    private String baseUrl;
    private AuthState auth;
    private final Map<String, AuthState> namedAuth = new HashMap<>();

    public Context(ExecutionOptions options) {
        this.options = options;
        this.baseUrl = options.getBaseUrl();
        this.variables.putAll(options.getVariables());
    }

    // Variables
    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    // Base URL
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String resolveUrl(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (baseUrl == null) {
            throw new IllegalStateException("baseUrl not set");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    // Auth
    public AuthState getAuth() {
        return auth;
    }

    public void setAuth(AuthState auth) {
        this.auth = auth;
    }

    public void setNamedAuth(String name, AuthState auth) {
        this.namedAuth.put(name, auth);
    }

    public AuthState getNamedAuth(String name) {
        return namedAuth.get(name);
    }

    // Environment
    public String getEnv(String name) {
        String value = options.getEnvironment().get(name);
        if (value == null) {
            value = System.getenv(name);
        }
        return value;
    }

    public String getEnv(String name, String defaultValue) {
        String value = getEnv(name);
        return value != null ? value : defaultValue;
    }

    public ExecutionOptions getOptions() {
        return options;
    }

    // Auth state interface - applies headers to a Map
    public sealed interface AuthState permits BearerAuth, BasicAuth, ApiKeyAuth {
        /**
         * Apply authentication headers to the given headers map.
         */
        void apply(Map<String, String> headers);
    }

    public record BearerAuth(String token) implements AuthState {
        @Override
        public void apply(Map<String, String> headers) {
            headers.put("Authorization", "Bearer " + token);
        }
    }

    public record BasicAuth(String username, String password) implements AuthState {
        @Override
        public void apply(Map<String, String> headers) {
            String credentials = username + ":" + password;
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("Authorization", "Basic " + encoded);
        }
    }

    public record ApiKeyAuth(String header, String value) implements AuthState {
        @Override
        public void apply(Map<String, String> headers) {
            headers.put(header, value);
        }
    }
}
