package org.bbrun.runtime;

import java.util.Collections;
import java.util.Map;

public record ResponseObject(
        int status,
        Map<String, String> headers,
        Object body,
        long time
) {
    public ResponseObject {
        // Header names are case-insensitive: response.headers.location reads Location
        java.util.TreeMap<String, String> named = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) named.putAll(headers);
        headers = Collections.unmodifiableMap(named);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
