package org.bbrun.config;

import java.util.Map;

public record ResourceAuth(
        String type,
        String token,
        String username,
        String password,
        String headerName,
        String headerValue,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String scope
) {
    public static ResourceAuth none() {
        return new ResourceAuth("none", null, null, null, null, null, null, null, null, null);
    }

    public static ResourceAuth bearer(String token) {
        return new ResourceAuth("bearer", token, null, null, null, null, null, null, null, null);
    }

    public static ResourceAuth basic(String username, String password) {
        return new ResourceAuth("basic", null, username, password, null, null, null, null, null, null);
    }

    public static ResourceAuth apiKey(String headerName, String headerValue) {
        return new ResourceAuth("apiKey", null, null, null, headerName, headerValue, null, null, null, null);
    }

    public static ResourceAuth oauth2(String tokenUrl, String clientId, String clientSecret, String scope) {
        return new ResourceAuth("oauth2", null, null, null, null, null, tokenUrl, clientId, clientSecret, scope);
    }

    @SuppressWarnings("unchecked")
    public static ResourceAuth fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return none();
        }
        String type = (String) map.getOrDefault("type", "none");
        String token = (String) map.get("token");
        String username = (String) map.get("username");
        String password = (String) map.get("password");
        String headerName = (String) map.get("headerName");
        String headerValue = (String) map.get("headerValue");
        String tokenUrl = map.get("tokenUrl") != null ? String.valueOf(map.get("tokenUrl")) : null;
        String clientId = (String) map.get("clientId");
        String clientSecret = (String) map.get("clientSecret");
        String scope = (String) map.get("scope");
        return new ResourceAuth(type, token, username, password, headerName, headerValue, tokenUrl, clientId, clientSecret, scope);
    }

    public boolean isOAuth2() {
        return "oauth2".equalsIgnoreCase(type);
    }
}