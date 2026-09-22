package io.github.lz007001cn.veriqra.config;

import java.util.Map;

/** Centralized lookup for Veriqra environment variables. */
public final class EnvironmentVariables {
    private EnvironmentVariables() { }

    public static String get(String suffix) {
        return get(System.getenv(), suffix);
    }

    public static String get(Map<String, String> environment, String suffix) {
        return environment.get("VERIQRA_" + suffix);
    }
}
