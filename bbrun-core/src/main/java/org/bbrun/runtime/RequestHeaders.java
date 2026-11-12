package org.bbrun.runtime;

import java.util.Map;

/**
 * Headers for one request, made by {@code headers({...})} in a script and passed as an argument of a
 * resource call: {@code api.get("/files/x", headers({"Accept-Language": "fr"}))}.
 */
public record RequestHeaders(Map<String, String> values) {
    public RequestHeaders {
        values = values != null ? Map.copyOf(values) : Map.of();
    }
}
