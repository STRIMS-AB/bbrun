package org.bbrun.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Protocol sink used by mock handler scripts: a `.bbrun` mock script produces
 * its response by calling the {@code respond(status, body)} / 
 * {@code respond(status, body, headers)} built-in. If the script never calls
 * {@code respond}, the mock uses its defaults (status 200, empty body).
 */
public class MockResponseSink {

    private int status = 200;
    private Object body;
    private Map<String, String> headers = Map.of();
    private boolean responded = false;

    public void respond(int status, Object body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers != null ? new HashMap<>(headers) : Map.of();
        this.responded = true;
    }

    public boolean isResponded() {
        return responded;
    }

    public int getStatus() {
        return status;
    }

    public Object getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}