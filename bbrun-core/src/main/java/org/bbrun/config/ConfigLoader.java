package org.bbrun.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    public static BBRunConfig load(Path path) {
        if (path == null || !Files.exists(path)) {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }
        try {
            String content = Files.readString(path);
            String interpolated = interpolateEnvVars(content);
            return parse(interpolated, path.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new ConfigurationException("Failed to read configuration file: " + path, e);
        }
    }

    public static BBRunConfig loadOrDefaults(Path path) {
        if (path != null && Files.exists(path)) {
            return load(path);
        }
        return emptyConfig();
    }

    @SuppressWarnings("unchecked")
    public static BBRunConfig parse(String yamlContent) {
        return parse(yamlContent, null);
    }

    @SuppressWarnings("unchecked")
    public static BBRunConfig parse(String yamlContent, Path baseDir) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlContent);
        if (root == null) {
            return emptyConfig();
        }

        String version = (String) root.getOrDefault("version", "1.0");
        String defaultEnv = (String) root.get("defaultEnvironment");

        // Parse environments
        Map<String, EnvironmentConfig> environments = new LinkedHashMap<>();
        Map<String, Object> envsMap = (Map<String, Object>) root.get("environments");
        if (envsMap != null) {
            for (Map.Entry<String, Object> envEntry : envsMap.entrySet()) {
                String envName = envEntry.getKey();
                Map<String, Object> envData = (Map<String, Object>) envEntry.getValue();

                Map<String, ResourceDefinition> resources = new LinkedHashMap<>();
                Map<String, Object> resMap = (Map<String, Object>) envData.get("resources");
                if (resMap != null) {
                    for (Map.Entry<String, Object> rEntry : resMap.entrySet()) {
                        String rName = rEntry.getKey();
                        Map<String, Object> rData = (Map<String, Object>) rEntry.getValue();

                        String type = rData.get("type") != null ? String.valueOf(rData.get("type")) : null;
                        String baseUrl = (String) rData.get("baseUrl");

                        Map<String, String> headers = new HashMap<>();
                        if (rData.get("headers") instanceof Map<?, ?> hMap) {
                            for (Map.Entry<?, ?> hEntry : hMap.entrySet()) {
                                headers.put(String.valueOf(hEntry.getKey()), String.valueOf(hEntry.getValue()));
                            }
                        }

                        String mapping = rData.get("mapping") instanceof String m
                                ? m : null;
                        if (rData.containsKey("mappings")) {
                            throw new ConfigurationException(
                                    "Resource '" + rName + "' declares 'mappings', which is not a key. "
                                    + "Declare a single 'mapping: <name>' instead, naming a mapping a model "
                                    + "declares with 'mapsFrom'.");
                        }

                        ResourceAuth auth = ResourceAuth.fromMap((Map<String, Object>) rData.get("auth"));

                        String mockKind = "mock".equals(type) ? parseMockKind(rName, rData) : null;
                        if (rData.containsKey("mock") && !"mock".equals(type)) {
                            throw new ConfigurationException(
                                    "Resource '" + rName + "' declares 'mock' but its type is '" + type + "': " +
                                    "'mock' is only valid on type: mock resources");
                        }
                        List<MockRoute> routes = loadMockRoutes(rName, "mock".equals(type), rData, baseDir, mockKind);

                        String dir = rData.get("dir") != null ? resolvePath(String.valueOf(rData.get("dir")), baseDir) : null;
                        Object seed = rData.get("data");
                        String from = (String) rData.get("from");

                        resources.put(rName, new ResourceDefinition(rName, type, baseUrl, headers, auth, mapping, routes, dir, seed, from, mockKind));
                    }
                }
                environments.put(envName, new EnvironmentConfig(envName, resources));
            }
        }

        // Domain models: a single DRef domain document, relative to the config
        // file. Defaults to "domain.json" next to the config. Mappings come from
        // the models themselves, via DRef's "mapsFrom".
        DrefConfig drefConfig = DrefConfig.defaults();
        if (root.get("domain") instanceof String domainPath && !domainPath.isBlank()) {
            drefConfig = new DrefConfig(domainPath);
        }
        if (root.containsKey("mappings")) {
            throw new ConfigurationException(
                    "'mappings' is not a configuration key. Declare each mapping on the model it imports "
                    + "onto, with 'mapsFrom', and bind a resource to it with 'mapping: <name>'.");
        }

        // logging: { dir: logs } — one log file per script run, in that folder (relative to the config)
        Path logDir = null;
        if (root.get("logging") instanceof Map<?, ?> logging) {
            for (Object key : logging.keySet()) {
                if (!"dir".equals(key)) {
                    throw new ConfigurationException("'logging' takes 'dir' (the folder for the log files), not '" + key + "'");
                }
            }
            if (logging.get("dir") instanceof String dir && !dir.isBlank()) {
                logDir = Path.of(resolvePath(dir, baseDir));
            }
        } else if (root.containsKey("logging")) {
            throw new ConfigurationException("'logging' is a map: logging: { dir: logs }");
        }

        return new BBRunConfig(version, defaultEnv, environments, drefConfig, baseDir, logDir);
    }

    public static BBRunConfig emptyConfig() {
        return new BBRunConfig("1.0", "default", Map.of(), DrefConfig.defaults());
    }

    /**
     * Resolves the routes of a mock resource, in order of precedence:
     * <ol>
     *   <li>inline {@code routes} in the config file;</li>
     *   <li>an explicit {@code mocks: <path>} value pointing to a mock configuration
     *       file (resolved against the config directory);</li>
     *   <li>convention: {@code mocks/<resource-name>.yaml} (or {@code .yml}) next to
     *       the config file, when the resource is {@code type: mock}.</li>
     * </ol>
     *
     * <p>Inside a mock file, relative {@code file} and {@code script} paths are
     * resolved against the mock file's own directory.
     */
    private static List<MockRoute> loadMockRoutes(String rName, boolean isMock,
                                                  Map<String, Object> rData, Path baseDir, String mockKind) {
        Object inline = rData.get("routes");
        Object mockSpec = rData.get("mocks");
        if (inline != null && mockSpec != null) {
            throw new ConfigurationException("Mock resource '" + rName + "' cannot have both inline 'routes' and a 'mocks' file");
        }
        if (inline != null) {
            return parseRoutes(inline, baseDir, mockKind);
        }
        if (mockSpec != null) {
            return loadMockFile(Path.of(resolvePath(String.valueOf(mockSpec), baseDir)), mockKind);
        }
        if (isMock && baseDir != null) {
            for (String ext : List.of("yaml", "yml")) {
                Path candidate = baseDir.resolve("mocks").resolve(rName + "." + ext);
                if (Files.exists(candidate)) {
                    return loadMockFile(candidate, mockKind);
                }
            }
        }
        return List.of();
    }

    private static List<MockRoute> loadMockFile(Path file, String mockKind) {
        try {
            if (!Files.exists(file)) {
                throw new ConfigurationException("Mock configuration file does not exist: " + file);
            }
            Yaml yaml = new Yaml();
            Object root = yaml.load(interpolateEnvVars(Files.readString(file)));
            if (root == null) {
                throw new ConfigurationException("Mock configuration file is empty: " + file);
            }
            Path routesBase = file.toAbsolutePath().getParent();
            if (root instanceof List<?> list) {
                return parseRoutes(list, routesBase, mockKind);
            }
            if (root instanceof Map<?, ?> map && map.get("routes") != null) {
                return parseRoutes(map.get("routes"), routesBase, mockKind);
            }
            throw new ConfigurationException(
                    "Mock configuration file '" + file.getFileName() + "' must be a route list or define 'routes'");
        } catch (IOException e) {
            throw new ConfigurationException("Failed to read mock configuration file: " + file, e);
        }
    }

    private static final Set<String> MOCK_KINDS = Set.of("http", "sql", "queue", "email", "file");

    /**
     * The kind a mock resource emulates; defaults to {@code http} (today's
     * behavior). Mocks of non-HTTP resources answer from {@code op}-based
     * routes only.
     */
    private static String parseMockKind(String rName, Map<String, Object> rData) {
        Object value = rData.get("mock");
        if (value == null) {
            return null;
        }
        String kind = String.valueOf(value).toLowerCase(Locale.ROOT);
        if (!MOCK_KINDS.contains(kind)) {
            throw new ConfigurationException("Resource '" + rName + "': unknown mock kind '" + value +
                    "'. Expected one of: " + MOCK_KINDS);
        }
        return kind;
    }

    @SuppressWarnings("unchecked")
    private static List<MockRoute> parseRoutes(Object raw, Path baseDir, String mockKind) {
        boolean httpMock = mockKind == null || "http".equals(mockKind);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new ConfigurationException("'routes' on a mock resource must be a list of route entries");
        }
        List<MockRoute> routes = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> routeMap)) {
                throw new ConfigurationException("Each mock route must be a map with 'path' and one response source");
            }
            Map<String, Object> rData = (Map<String, Object>) routeMap;

            Set<String> methods = new LinkedHashSet<>();
            if (rData.get("method") != null) {
                methods.add(String.valueOf(rData.get("method")).toLowerCase(Locale.ROOT));
            }
            if (rData.get("methods") instanceof List<?> methodList) {
                for (Object m : methodList) {
                    methods.add(String.valueOf(m).toLowerCase(Locale.ROOT));
                }
            }

            Object status = rData.get("status");
            Integer statusCode = status instanceof Number num ? num.intValue() : null;

            Map<String, Object> headers = new HashMap<>();
            if (rData.get("headers") instanceof Map<?, ?> headerMap) {
                for (Map.Entry<?, ?> h : headerMap.entrySet()) {
                    headers.put(String.valueOf(h.getKey()), h.getValue());
                }
            }

            String path = rData.get("path") != null ? String.valueOf(rData.get("path")) : null;
            String op = rData.get("op") != null ? String.valueOf(rData.get("op")).toLowerCase(Locale.ROOT) : null;
            if (httpMock && op != null) {
                throw new ConfigurationException("Route on HTTP mock cannot use 'op' (use 'method'/'path')");
            }
            if (!httpMock) {
                if (path != null || !methods.isEmpty()) {
                    throw new ConfigurationException("Route on " + mockKind + " mock must use 'op', not 'method'/'path'");
                }
                if (op == null) {
                    throw new ConfigurationException("Route on " + mockKind + " mock must declare 'op'");
                }
                statusCode = null;
            }
            String when = rData.get("when") != null ? String.valueOf(rData.get("when")) : null;
            Object data = rData.get("data");
            String file = rData.get("file") != null ? resolvePath(String.valueOf(rData.get("file")), baseDir) : null;
            String expr = rData.get("expr") != null ? String.valueOf(rData.get("expr")) : null;
            String script = rData.get("script") != null ? resolvePath(String.valueOf(rData.get("script")), baseDir) : null;

            int sources = 0;
            if (data != null) sources++;
            if (file != null) sources++;
            if (expr != null) sources++;
            if (script != null) sources++;
            if (sources > 1) {
                throw new ConfigurationException("Mock route '" + path + "' can only use one of: data, file, expr, script");
            }

            routes.add(new MockRoute(methods, path, statusCode, data, file, expr, script, headers, when, op));
        }
        return routes;
    }

    private static String resolvePath(String value, Path baseDir) {
        Path p = Path.of(value);
        if (baseDir != null && !p.isAbsolute()) {
            p = baseDir.resolve(p).normalize();
        }
        return p.toString();
    }

    public static String interpolateEnvVars(String text) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fullExpr = matcher.group(1).trim();
            String varName = fullExpr;
            String defaultValue = "";

            int colonIdx = fullExpr.indexOf(":-");
            if (colonIdx >= 0) {
                varName = fullExpr.substring(0, colonIdx).trim();
                defaultValue = fullExpr.substring(colonIdx + 2).trim();
            }

            String val = System.getenv(varName);
            if (val == null) {
                val = System.getProperty(varName);
            }
            if (val == null) {
                val = defaultValue;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
