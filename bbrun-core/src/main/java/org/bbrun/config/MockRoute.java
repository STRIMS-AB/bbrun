package org.bbrun.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A configured mock response for a resource.
 *
 * <p>Maps a request (verb + path pattern) to mock data. Exactly one of
 * {@code data}, {@code file}, {@code expr} or {@code script} should be set:
 * <ul>
 *   <li>{@code data}   - a literal YAML value used as the response body;</li>
 *   <li>{@code file}   - a JSON fixture file (resolved against the config dir);</li>
 *   <li>{@code expr}   - a BBRun expression evaluated against the mock inputs
 *       ({@code verb}, {@code path}, {@code body}, {@code params}, {@code query},
 *       {@code headers}) whose value is the response body;</li>
 *   <li>{@code script} - a BBRun handler script that produces the response via
 *       {@code respond(status, body)}. The script runs WITHOUT the project's
 *       domain models - mocks simulate the other side of the wire.</li>
 * </ul>
 *
 * <p>{@code when} is an optional BBRun condition (same bindings as {@code expr})
 * evaluated during route matching: the route only applies when the condition is
 * truthy, otherwise matching continues with the next route in declaration order.
 * This is how a mock answers differently per test case - the engine sends every
 * request with an {@code x-bbrun-script} header naming the running script, so a
 * route like {@code when: 'headers["x-bbrun-script"] == "05-down.bbrun"'} with
 * {@code status: 500} triggers a failure only for the script that expects it.
 */
public record MockRoute(
        Set<String> methods,
        String path,
        Integer status,
        Object data,
        String file,
        String expr,
        String script,
        Map<String, Object> headers,
        String when,
        String op
) {
    public MockRoute {
        methods = methods != null ? Set.copyOf(methods) : Set.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    public MockRoute(
            Set<String> methods,
            String path,
            Integer status,
            Object data,
            String file,
            String expr,
            String script,
            Map<String, Object> headers) {
        this(methods, path, status, data, file, expr, script, headers, null, null);
    }

    public MockRoute(
            Set<String> methods,
            String path,
            Integer status,
            Object data,
            String file,
            String expr,
            String script,
            Map<String, Object> headers,
            String when) {
        this(methods, path, status, data, file, expr, script, headers, when, null);
    }

    public boolean matchesVerb(String verb) {
        String normalizedVerb = verb.toLowerCase(Locale.ROOT);
        return methods.isEmpty() || methods.contains(normalizedVerb);
    }

    public Set<String> normalizedMethods() {
        Set<String> result = new LinkedHashSet<>();
        for (String m : methods) {
            result.add(m.toLowerCase(Locale.ROOT));
        }
        return result;
    }
}