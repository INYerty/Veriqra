package io.github.lz007001cn.veriqra.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Immutable pool configuration. Never includes credentials in its string representation. */
public final class DatabaseConfig {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int initialPoolSize;
    private final int maxPoolSize;
    private final Duration acquireTimeout;

    public DatabaseConfig(String jdbcUrl, String username, String password,
                          int initialPoolSize, int maxPoolSize, Duration acquireTimeout) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("jdbcUrl must be a MySQL JDBC URL");
        }
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username is required");
        if (password == null) throw new IllegalArgumentException("password must be configured externally");
        if (initialPoolSize < 0 || maxPoolSize < 1 || initialPoolSize > maxPoolSize) {
            throw new IllegalArgumentException("Require 0 <= initialPoolSize <= maxPoolSize and maxPoolSize >= 1");
        }
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (acquireTimeout.compareTo(Duration.ofMillis(1)) < 0
                || acquireTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("acquireTimeout must be in (0, 300000] milliseconds");
        }
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.initialPoolSize = initialPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.acquireTimeout = acquireTimeout;
    }

    /** Defaults < optional external UTF-8 file < VERIQRA_DB_* environment variables. */
    public static DatabaseConfig load() {
        String selected = EnvironmentVariables.get("DB_CONFIG");
        if (selected == null) selected = System.getProperty("veriqra.db.config");
        Path path = selected == null ? Path.of("config/database.local.properties") : Path.of(selected);
        if (selected != null && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Selected database configuration file is missing");
        }
        return load(path, System.getenv());
    }

    /** Explicit external path and environment, also useful for deterministic configuration tests. */
    public static DatabaseConfig load(Path externalFile, Map<String, String> environment) {
        Properties p = new Properties();
        try (InputStream in = DatabaseConfig.class.getResourceAsStream("/database.properties")) {
            if (in == null) throw new IllegalStateException("Missing database.properties defaults");
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            if (externalFile != null && Files.exists(externalFile)) {
                try (var reader = Files.newBufferedReader(externalFile, StandardCharsets.UTF_8)) { p.load(reader); }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load database configuration", e);
        }
        Map<String, String> keys = Map.of("jdbcUrl", "JDBC_URL", "username", "USERNAME",
                "password", "PASSWORD", "initialPoolSize", "INITIAL_POOL_SIZE", "maxPoolSize", "MAX_POOL_SIZE",
                "acquireTimeout", "ACQUIRE_TIMEOUT");
        keys.forEach((key, suffix) -> {
            String value = EnvironmentVariables.get(environment, "DB_" + suffix);
            if (value != null) p.setProperty(key, value);
        });
        try {
            return new DatabaseConfig(p.getProperty("jdbcUrl"), p.getProperty("username"), p.getProperty("password"),
                    Integer.parseInt(p.getProperty("initialPoolSize")), Integer.parseInt(p.getProperty("maxPoolSize")),
                    Duration.ofMillis(Long.parseLong(p.getProperty("acquireTimeout"))));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Pool sizes and acquireTimeout must be integer values");
        }
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public int initialPoolSize() { return initialPoolSize; }
    public int maxPoolSize() { return maxPoolSize; }
    public Duration acquireTimeout() { return acquireTimeout; }
    @Override public String toString() { return "DatabaseConfig[credentials and URL redacted]"; }
}
