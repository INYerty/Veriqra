package io.github.lz007001cn.veriqra.web;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/** Safe, allowlisted metadata embedded when Maven builds the WAR. */
public record BuildInfo(String version, String commit, String buildTime) {
    private static final BuildInfo CURRENT = loadResource();

    public static BuildInfo current() { return CURRENT; }

    private static BuildInfo loadResource() {
        try (InputStream input = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            return load(input);
        } catch (IOException e) { return unknown(); }
    }

    public static BuildInfo load(InputStream input) throws IOException {
        if (input == null) return unknown();
        Properties values = new Properties();
        values.load(input);
        String version = values.getProperty("version", "");
        String commit = values.getProperty("commit", "");
        String buildTime = values.getProperty("buildTime", "");
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) version = "unknown";
        if (!commit.matches("[0-9a-fA-F]{40}")) commit = "unknown";
        try { Instant.parse(buildTime); }
        catch (DateTimeParseException e) { buildTime = "unknown"; }
        return new BuildInfo(version, commit, buildTime);
    }

    private static BuildInfo unknown() { return new BuildInfo("unknown", "unknown", "unknown"); }
}
