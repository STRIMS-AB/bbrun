package org.bbrun.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ResourceDefinition(
        String name,
        String type,
        String baseUrl,
        Map<String, String> headers,
        ResourceAuth auth,
        String mapping,
        List<MockRoute> routes,
        String dir,
        Object seed,
        String from,
        String mock
) {
    public ResourceDefinition(
            String name, String type, String baseUrl, Map<String, String> headers, ResourceAuth auth) {
        this(name, type, baseUrl, headers, auth, null, List.of(), null, null, null, null);
    }

    /** A resource that translates its inbound responses with the named mapping. */
    public ResourceDefinition(
            String name, String type, Map<String, String> headers, String mapping) {
        this(name, type, null, headers, ResourceAuth.none(), mapping, List.of(), null, null, null, null);
    }

    public ResourceDefinition(
            String name, String type, String baseUrl, Map<String, String> headers, ResourceAuth auth,
            List<MockRoute> routes) {
        this(name, type, baseUrl, headers, auth, null, routes, null, null, null, null);
    }

    /** A resource with mock routes and a bound mapping. */
    public ResourceDefinition(
            String name, String type, String baseUrl, Map<String, String> headers, ResourceAuth auth,
            String mapping, List<MockRoute> routes) {
        this(name, type, baseUrl, headers, auth, mapping, routes, null, null, null, null);
    }

    public ResourceDefinition(String name, String type, String baseUrl, Map<String, String> headers,
                              ResourceAuth auth, List<MockRoute> routes,
                              String dir, Object seed, String from) {
        this(name, type, baseUrl, headers, auth, null, routes, dir, seed, from, null);
    }

    public ResourceDefinition {
        headers = headers != null ? Map.copyOf(headers) : Map.of();
        auth = auth != null ? auth : ResourceAuth.none();
        mapping = mapping != null && !mapping.isBlank() ? mapping : null;
        routes = routes != null ? List.copyOf(routes) : List.of();
        mock = mock != null ? mock.toLowerCase(Locale.ROOT) : "http";
    }

    public String resolvedType() {
        if (type != null && !type.isBlank()) {
            return type.toLowerCase(Locale.ROOT);
        }
        if (baseUrl != null && baseUrl.startsWith("mock://")) {
            return "mock";
        }
        return "http";
    }

    /** What kind of thing this mock emulates (http, sql, queue, email, file). */
    public String mockKind() {
        return mock;
    }

    public dev.iconn.api.ResourceDefinition toIconnDefinition(java.nio.file.Path baseLocation) {
        String resType = resolvedType();
        String iconnType;
        String iconnProvider;
        if ("sql".equals(resType) || "database".equals(resType)) {
            iconnType = "database";
            iconnProvider = "embedded";
        } else if ("file".equals(resType)) {
            iconnType = "file";
            iconnProvider = "local";
        } else if ("queue".equals(resType)) {
            iconnType = "queue";
            iconnProvider = "inmemory";
        } else if ("email".equals(resType)) {
            iconnType = "email";
            iconnProvider = "mock";
        } else if ("mock".equals(resType)) {
            iconnType = "http";
            iconnProvider = "mock";
        } else {
            iconnType = "http";
            iconnProvider = (baseUrl != null && baseUrl.startsWith("mock://")) ? "mock" : "real";
        }

        Map<String, Object> config = new java.util.LinkedHashMap<>();
        if (baseUrl != null) {
            config.put("baseUrl", baseUrl);
            config.put("url", baseUrl);
        }
        if (headers != null && !headers.isEmpty()) {
            config.put("headers", headers);
        }
        if (dir != null) {
            config.put("dir", dir);
        }
        if (seed != null) {
            config.put("data", seed);
            config.put("seed", seed);
        }
        if (from != null) {
            config.put("from", from);
        }
        if (mock != null) {
            config.put("mock", mock);
        }
        if (auth != null && auth.type() != null) {
            Map<String, Object> authMap = new java.util.LinkedHashMap<>();
            authMap.put("type", auth.type());
            if (auth.token() != null) authMap.put("token", auth.token());
            if (auth.username() != null) authMap.put("username", auth.username());
            if (auth.password() != null) authMap.put("password", auth.password());
            if (auth.tokenUrl() != null) authMap.put("tokenUrl", auth.tokenUrl());
            if (auth.clientId() != null) authMap.put("clientId", auth.clientId());
            if (auth.clientSecret() != null) authMap.put("clientSecret", auth.clientSecret());
            if (auth.scope() != null) authMap.put("scope", auth.scope());
            // iconn names an API key's header and value keyName / keyValue
            if (auth.headerName() != null) authMap.put("keyName", auth.headerName());
            if (auth.headerValue() != null) authMap.put("keyValue", auth.headerValue());
            config.put("auth", authMap);
            config.put("security", authMap);
        }

        String baseLoc = baseLocation != null ? baseLocation.toString() : "";
        return new dev.iconn.api.ResourceDefinition(name, iconnType, iconnProvider, config, baseLoc);
    }
}