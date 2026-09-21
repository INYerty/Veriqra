package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.web.json.JsonMapperProvider;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual deployment descriptor/listener/Servlet/Service/DAO/MySQL pipeline, on fixture-owned schema only. */
class AuthHttpIntegrationTest extends MysqlFixture {
    @TempDir static Path directory;
    Tomcat tomcat;
    HttpClient client;
    String base;
    String cookie;
    String hash;
    String previousConfig;
    User admin;
    User tester;
    User disabled;

    @BeforeAll void startWebApplication() throws Exception {
        // Fixture already validates local test URL, version, scoped account and empty schema.
        previousConfig = System.getProperty("qatrack.db.config");
        if (System.getenv("QATRACK_DB_CONFIG") != null
                || System.getenv().keySet().stream().anyMatch(k -> k.startsWith("QATRACK_DB_"))) {
            throw new IllegalStateException("Remove application DB environment overrides for isolated web tests");
        }
        Properties properties = new Properties();
        properties.setProperty("jdbcUrl", config.jdbcUrl());
        properties.setProperty("username", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("initialPoolSize", "1");
        properties.setProperty("maxPoolSize", "3");
        properties.setProperty("acquireTimeout", "2000");
        Path external = directory.resolve("database.local.properties");
        try (var writer = Files.newBufferedWriter(external)) { properties.store(writer, "Isolated test only"); }
        System.setProperty("qatrack.db.config", external.toString());
        tomcat = new Tomcat();
        // This API-only embedded runtime has no Jasper/JSP dependency.
        tomcat.setAddDefaultWebXmlToWebapp(false);
        tomcat.setBaseDir(directory.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addWebapp("/qatrack", Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(getClass().getClassLoader());
        tomcat.start();
        assertTrue(context.getState().isAvailable(), "Production deployment descriptor must start successfully");
        base = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/qatrack/api";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec("fixture-login-password".toCharArray(), salt, 600000, 256);
        try {
            hash = "pbkdf2_sha256$600000$" + HexFormat.of().formatHex(salt) + "$"
                    + HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } finally { spec.clearPassword(); }
    }

    @AfterAll void stopWebApplication() throws Exception {
        try {
            if (tomcat != null) { try { tomcat.stop(); } finally { tomcat.destroy(); } }
        } finally {
            if (previousConfig == null) System.clearProperty("qatrack.db.config");
            else System.setProperty("qatrack.db.config", previousConfig);
        }
    }

    @BeforeEach void users() {
        cookie = null;
        admin = insert("web_admin", SystemRole.ADMIN, UserStatus.ACTIVE);
        tester = insert("web_tester", SystemRole.USER, UserStatus.ACTIVE);
        disabled = insert("web_disabled", SystemRole.USER, UserStatus.DISABLED);
    }
    User insert(String username, SystemRole role, UserStatus status) {
        return tx.inTransaction(c -> new JdbcUserDao(c).insert(new User(null, username, username, hash,
                role, status, null, null, null)));
    }
    HttpResponse<String> call(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("X-QATrack-Request", "1");
        request.header("Origin", "http://" + URI.create(base).getRawAuthority());
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    void login(User user) throws Exception {
        var response = call("POST", "/auth/login", "{\"username\":\"" + user.username() + "\",\"password\":\"fixture-login-password\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("password")); assertFalse(response.body().contains("lockVersion"));
        assertTrue(response.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly"));
        assertTrue(response.headers().firstValue("set-cookie").orElseThrow().contains("SameSite=Lax"));
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
    long createProject() throws Exception {
        login(admin);
        var response = call("POST", "/projects", "{\"projectKey\":\"WEB\",\"name\":\"中文项目\"}");
        assertEquals(201, response.statusCode(), response.body());
        return JsonMapperProvider.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                .<com.fasterxml.jackson.databind.JsonNode>readValue(response.body()).get("id").asLong();
    }

    @Test void realPasswordsDisabledUsersAndLogout() throws Exception {
        assertEquals(401, call("POST", "/auth/login", "{\"username\":\"非法\",\"password\":\"wrong\"}").statusCode());
        assertEquals(401, call("POST", "/auth/login", "{\"username\":\"web_admin\",\"password\":\"wrong\"}").statusCode());
        assertEquals(401, call("POST", "/auth/login", "{\"username\":\"missing\",\"password\":\"wrong\"}").statusCode());
        assertEquals(401, call("POST", "/auth/login", "{\"username\":\"web_disabled\",\"password\":\"fixture-login-password\"}").statusCode());
        login(admin);
        assertEquals(200, call("GET", "/auth/me", null).statusCode());
        assertEquals(204, call("POST", "/auth/logout", null).statusCode());
        assertEquals(401, call("GET", "/auth/me", null).statusCode());
    }

    @Test void realProjectRequirementValidationAndCurrentMembership() throws Exception {
        long project = createProject();
        assertEquals(409, call("POST", "/projects", "{\"projectKey\":\"WEB\",\"name\":\"duplicate\"}").statusCode());
        assertEquals(400, call("POST", "/projects", "{\"projectKey\":\"bad-key\",\"name\":\"x\"}").statusCode());
        assertEquals(404, call("GET", "/projects/9999999", null).statusCode());
        assertTrue(call("GET", "/projects", null).body().contains("中文项目"));
        tx.inTransaction(c -> new JdbcProjectMemberDao(c).add(new ProjectMember(project, tester.id(),
                ProjectRole.TESTER, MembershipStatus.ACTIVE, null, null, null)));
        login(tester);
        assertEquals(403, call("POST", "/projects", "{\"projectKey\":\"BAD\",\"name\":\"x\"}").statusCode());
        assertTrue(call("GET", "/projects", null).body().contains("中文项目"));
        assertEquals(201, call("POST", "/projects/" + project + "/requirements", "{\"title\":\"中文需求\",\"priority\":\"HIGH\"}").statusCode());
        assertTrue(call("GET", "/projects/" + project + "/requirements", null).body().contains("中文需求"));
        assertEquals(400, call("POST", "/projects/" + project + "/requirements", "{\"title\":\"\",\"priority\":\"HIGH\"}").statusCode());
        assertEquals(400, call("POST", "/projects/" + project + "/requirements",
                "{\"title\":\"forged\",\"priority\":\"HIGH\",\"actorUserId\":" + admin.id() + "}").statusCode());
        tx.inTransaction(c -> {
            var dao = new JdbcProjectMemberDao(c);
            var member = dao.find(project, tester.id()).orElseThrow();
            dao.update(new ProjectMember(project, tester.id(), ProjectRole.TESTER, MembershipStatus.INACTIVE,
                    member.joinedAt(), member.updatedAt(), member.lockVersion()));
            return null;
        });
        assertEquals("[]", call("GET", "/projects", null).body());
        assertEquals(403, call("GET", "/projects/" + project, null).statusCode());
        assertEquals(403, call("POST", "/projects/" + project + "/requirements", "{\"title\":\"blocked\",\"priority\":\"HIGH\"}").statusCode());
    }

    @Test void sessionDoesNotCacheSystemRoleOrUserStatus() throws Exception {
        login(admin);
        tx.inTransaction(c -> {
            var dao = new JdbcUserDao(c);
            var user = dao.findById(admin.id()).orElseThrow();
            dao.update(new User(user.id(), user.username(), user.displayName(), user.passwordHash(), SystemRole.USER,
                    UserStatus.ACTIVE, user.createdAt(), user.updatedAt(), user.lockVersion()));
            return null;
        });
        assertTrue(call("GET", "/auth/me", null).body().contains("\"systemRole\":\"USER\""));
        assertEquals(403, call("POST", "/projects", "{\"projectKey\":\"DENIED\",\"name\":\"x\"}").statusCode());
        tx.inTransaction(c -> {
            var dao = new JdbcUserDao(c);
            var user = dao.findById(admin.id()).orElseThrow();
            dao.update(new User(user.id(), user.username(), user.displayName(), user.passwordHash(), user.systemRole(),
                    UserStatus.DISABLED, user.createdAt(), user.updatedAt(), user.lockVersion()));
            return null;
        });
        assertEquals(401, call("GET", "/auth/me", null).statusCode());
        assertEquals(401, call("GET", "/projects", null).statusCode());
    }
}
