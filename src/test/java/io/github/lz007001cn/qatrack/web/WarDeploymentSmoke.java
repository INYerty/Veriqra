package io.github.lz007001cn.qatrack.web;

import org.apache.catalina.startup.Tomcat;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;

/**
 * Run AFTER package, with ONLY tomcat-embed-core on launcher classpath.
 * Java source launcher: WarDeploymentSmoke.java <war> <test-config>.
 * Keeping application/JDBC classes out of the parent loader detects deployment-only driver issues.
 */
public final class WarDeploymentSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected WAR and isolated test configuration");
        Properties properties = new Properties();
        Path config = Path.of(args[1]).toAbsolutePath();
        try (var reader = Files.newBufferedReader(config)) { properties.load(reader); }
        if (!properties.getProperty("jdbcUrl", "").matches(
                "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):13307/qatrack_test_[a-z0-9_]+")
                || properties.getProperty("username", "").isBlank()
                || "root".equalsIgnoreCase(properties.getProperty("username"))
                || System.getenv().keySet().stream().anyMatch(key -> key.startsWith("QATRACK_DB_"))) {
            throw new IllegalArgumentException("WAR smoke requires isolated port 13307 test-schema config and no application DB overrides");
        }
        System.setProperty("qatrack.db.config", config.toString());
        // Replicate Tomcat-wide JDBC initialization before the webapp classloader exists.
        java.sql.DriverManager.getDrivers();
        for (int round = 0; round < 2; round++) deploy(Path.of(args[0]).toAbsolutePath());
        System.out.println("WAR_SMOKE_PASS: two independent deployment/shutdown cycles; protected API returns 401 JSON");
    }

    private static void deploy(Path war) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "war-smoke-");
        Files.createDirectories(directory.resolve("webapps"));
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toAbsolutePath().toString());
        tomcat.setAddDefaultWebXmlToWebapp(false); // API-only embedded runtime: no Jasper.
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addWebapp("/qatrack", war.toString());
        try {
            tomcat.start();
            if (!context.getState().isAvailable()) throw new AssertionError("WAR deployment failed");
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort()
                        + "/qatrack/api/auth/me")).timeout(Duration.ofSeconds(10)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 401 || !response.body().contains("UNAUTHENTICATED")) {
                    throw new AssertionError("Unexpected protected response status: " + response.statusCode());
                }
            }
        } finally { try { tomcat.stop(); } finally { tomcat.destroy(); } }
    }
}
