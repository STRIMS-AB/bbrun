package org.bbrun.config;

public record DrefConfig(String domain) {
    public static DrefConfig defaults() {
        return new DrefConfig("domain.json");
    }
}
