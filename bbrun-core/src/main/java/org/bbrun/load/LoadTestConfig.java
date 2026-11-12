package org.bbrun.load;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Parameters that bound a single {@link LoadTestRunner} run.
 *
 * <p>Either {@code iterations} or {@code duration} (or both) must be provided;
 * the run stops as soon as one is satisfied.</p>
 */
public record LoadTestConfig(
        Path script,
        int users,
        int iterations,
        Duration duration,
        Duration rampUp,
        Path configPath,
        String environment
) {

    public LoadTestConfig {
        if (script == null) {
            throw new IllegalArgumentException("script is required");
        }
        if (users <= 0) {
            throw new IllegalArgumentException("users must be > 0");
        }
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0");
        }
        if (iterations == 0 && duration == null) {
            throw new IllegalArgumentException("Provide --iterations, --duration, or both to bound the load test");
        }
        if (duration != null && (duration.isNegative() || duration.isZero())) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (rampUp != null && rampUp.isNegative()) {
            throw new IllegalArgumentException("ramp-up must be positive or zero");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path script;
        private int users = 1;
        private int iterations;
        private Duration duration;
        private Duration rampUp;
        private Path configPath;
        private String environment;

        public Builder script(Path script) {
            this.script = script;
            return this;
        }

        public Builder users(int users) {
            this.users = users;
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder rampUp(Duration rampUp) {
            this.rampUp = rampUp;
            return this;
        }

        public Builder configPath(Path configPath) {
            this.configPath = configPath;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public LoadTestConfig build() {
            return new LoadTestConfig(script, users, iterations, duration, rampUp, configPath, environment);
        }
    }
}