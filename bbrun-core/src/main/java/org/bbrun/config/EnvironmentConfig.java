package org.bbrun.config;

import java.util.*;

public record EnvironmentConfig(
        String name,
        Map<String, ResourceDefinition> resources
) {
    public EnvironmentConfig {
        resources = resources != null ? Map.copyOf(resources) : Map.of();
    }

    public ResourceDefinition getResource(String resourceName) {
        ResourceDefinition def = resources.get(resourceName);
        if (def == null) {
            throw new ConfigurationException(
                    "Unknown resource '" + resourceName + "' in environment '" + name + "'. " +
                    "Available resources: " + resources.keySet()
            );
        }
        return def;
    }

    public boolean hasResource(String resourceName) {
        return resources.containsKey(resourceName);
    }
}
