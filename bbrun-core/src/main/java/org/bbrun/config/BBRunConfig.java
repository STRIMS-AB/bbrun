package org.bbrun.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * The parsed bbrun configuration.
 *
 * @param basePath directory of the config file; all config relative paths
 *                 (domain file, mappings, mocks, log folder) resolve against it (null
 *                 when the config was built programmatically)
 * @param logDir   {@code logging.dir}: the folder the CLI writes one log file per script
 *                 into; null when the config names none
 */
public record BBRunConfig(
        String version,
        String defaultEnvironment,
        Map<String, EnvironmentConfig> environments,
        DrefConfig dref,
        Path basePath,
        Path logDir
) {
    public BBRunConfig {
        environments = environments != null ? Map.copyOf(environments) : Map.of();
        dref = dref != null ? dref : DrefConfig.defaults();
    }

    public BBRunConfig(String version, String defaultEnvironment,
                       Map<String, EnvironmentConfig> environments, DrefConfig dref, Path basePath) {
        this(version, defaultEnvironment, environments, dref, basePath, null);
    }

    public BBRunConfig(String version, String defaultEnvironment,
                       Map<String, EnvironmentConfig> environments, DrefConfig dref) {
        this(version, defaultEnvironment, environments, dref, null);
    }

    public EnvironmentConfig getEnvironment(String envName) {
        String targetEnv = (envName != null && !envName.isBlank()) ? envName : defaultEnvironment;
        if (targetEnv == null) {
            targetEnv = environments.keySet().stream().findFirst().orElse("default");
        }
        EnvironmentConfig env = environments.get(targetEnv);
        if (env == null) {
            throw new ConfigurationException(
                    "Target environment '" + targetEnv + "' not found in configuration. " +
                    "Defined environments: " + environments.keySet()
            );
        }
        return env;
    }

    public Path baseDir() {
        return basePath;
    }
}
