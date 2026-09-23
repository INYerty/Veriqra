package io.github.lz007001cn.veriqra.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.auth.PasswordVerifier;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.importing.JUnitXmlParser;
import io.github.lz007001cn.veriqra.service.support.*;
import io.github.lz007001cn.veriqra.web.*;
import io.github.lz007001cn.veriqra.web.json.JsonMapperProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4 proof through real HTTP, Services, transactions and MySQL. */
class AutomationImportHttpIntegrationTest extends MysqlFixture {
    @TempDir static Path directory;
    private EmbeddedWebServer server;
    private HttpClient client;
    private String cookie;
    private String passwordHash;
    private User admin;
    private User tester;
    private User developer;
    private Project project;
    private ProjectService projects;
    private TestCaseService testCases;

    @BeforeAll void startHttp() throws Exception {
        if (!config.jdbcUrl().matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/veriqra_test_[a-z0-9_]+")) {
            throw new IllegalStateException("Automation HTTP tests require an isolated test fixture");
        }
        var transactions = new JdbcServiceTransaction(tx);
        var daos = new JdbcServiceDaoFactory();
        var access = new ProjectAccessPolicy();
        projects = new DefaultProjectService(transactions, daos, access);
        testCases = new DefaultTestCaseService(transactions, daos, access);
        var automation = new DefaultAutomationService(transactions, daos, access);
        var imports = new DefaultTestImportService(transactions, daos, access, new JUnitXmlParser(), Clock.systemUTC());
        server = new EmbeddedWebServer(directory, new WebServices(
                new DefaultAuthService(transactions, daos, new PasswordVerifier()), projects,
                new DefaultRequirementService(transactions, daos, access), testCases,
                new DefaultTraceabilityService(transactions, daos, access, Clock.systemUTC()),
                new DefaultTestPlanService(transactions, daos, access),
                new DefaultTestRunService(transactions, daos, access, Clock.systemUTC()),
                new DefaultTestExecutionService(transactions, daos, access, Clock.systemUTC()),
                new DefaultDefectService(transactions, daos, access), automation, imports), false, "");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec("round4-test-password".toCharArray(), salt, 600000, 256);
        try {
            passwordHash = "pbkdf2_sha256$600000$" + HexFormat.of().formatHex(salt) + "$"
                    + HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    @AfterAll void stopHttp() throws Exception {
        if (server != null) server.close();
        if (client != null) client.close();
    }

    @BeforeEach void bootstrap() {
        cookie = null;
        admin = account("r4-admin", SystemRole.ADMIN);
        tester = account("r4-tester", SystemRole.USER);
        developer = account("r4-developer", SystemRole.USER);
        project = projects.create(admin.id(), new CreateProjectCommand("R4", "Round 4", null,
                tester.id(), ProjectRole.TESTER));
        tx.inTransaction(connection -> {
            new JdbcProjectMemberDao(connection).add(new ProjectMember(project.id(), developer.id(),
                    ProjectRole.DEVELOPER, MembershipStatus.ACTIVE, null, null, null));
            return null;
        });
    }

    @Test void previewHasNoSideEffectsThenConfirmedMappingsImportAllOutcomesAndReplay() throws Exception {
        login(developer);
        byte[] unknown = xml("example.Round4", "<testcase classname=\"example.Round4\" name=\"unknown\"/>");
        JsonNode preview = expect(200, "POST", previewPath(project.id(), "example.Round4"),
                unknown, "application/xml", true, origin());
        assertFalse(preview.get("readyToImport").asBoolean());
        assertEquals("unknown", preview.at("/unknownIdentities/0/externalKey").asText());
        assertEquals(0, rowCount("test_automation_identities"));
        assertEquals(0, rowCount("test_runs"));
        assertEquals(0, rowCount("test_imports"));

        login(tester);
        List<String> names = List.of("passed", "failure", "error", "skipped");
        for (String name : names) {
            TestCase testCase = readyCase(name);
            JsonNode identity = expect(201, "POST", "/projects/" + project.id() + "/automation/identities",
                    json("{\"source\":\"JUNIT\",\"namespace\":\"example.Round4\",\"externalKey\":\"" + name + "\"}"),
                    "application/json", true, origin());
            JsonNode mapping = expect(200, "PUT", "/automation/identities/" + identity.get("id").asLong() + "/mapping",
                    json("{\"testCaseId\":" + testCase.id() + ",\"expectedVersion\":null}"),
                    "application/json", true, origin());
            assertEquals("ACTIVE", mapping.get("status").asText());
        }
        assertEquals(4, expect(200, "GET", "/projects/" + project.id() + "/automation/identities",
                null, null, false, null).size());
        assertEquals(4, expect(200, "GET", "/projects/" + project.id() + "/automation/mappings",
                null, null, false, null).size());

        byte[] report = xml("example.Round4",
                "<testcase classname=\"example.Round4\" name=\"passed\" time=\"0.125\"/>",
                "<testcase classname=\"example.Round4\" name=\"failure\"><failure message=\"bad\">trace</failure></testcase>",
                "<testcase classname=\"example.Round4\" name=\"error\"><error message=\"boom\">stack</error></testcase>",
                "<testcase classname=\"example.Round4\" name=\"skipped\"><skipped message=\"disabled\"/></testcase>");
        preview = expect(200, "POST", previewPath(project.id(), "example.Round4"), report,
                "application/xml;charset=UTF-8", true, origin());
        assertTrue(preview.get("readyToImport").asBoolean());
        assertEquals(4, preview.get("mappedResults").size());

        UUID key = UUID.randomUUID();
        String importPath = importPath(project.id(), key, "example.Round4");
        HttpResponse<String> created = call("POST", importPath, report, "application/xml", true, origin());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode result = parse(created.body());
        assertFalse(result.get("replayed").asBoolean());
        assertEquals("COMPLETED", result.at("/run/status").asText());
        assertEquals(64, result.at("/testImport/reportSha256").asText().length());
        assertEquals(4, result.get("runCases").size());
        assertEquals(List.of("PASS", "FAIL", "FAIL", "SKIPPED"),
                result.get("attempts").valueStream().map(value -> value.get("outcome").asText()).toList());
        assertTrue(result.get("attempts").valueStream()
                .anyMatch(value -> value.path("failureMessage").asText().startsWith("[JUnit error]")));
        long runId = result.at("/run/id").asLong();
        assertEquals("/api/projects/" + project.id() + "/runs/" + runId,
                created.headers().firstValue("Location").orElseThrow());

        JsonNode replay = expect(200, "POST", importPath, report, "application/xml", true, origin());
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(result.at("/testImport/id"), replay.at("/testImport/id"));
        byte[] differentReport = (new String(report, StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(409, call("POST", importPath, differentReport,
                "application/xml", true, origin()).statusCode());
        assertEquals(1, rowCount("test_imports"));
        assertEquals(1, rowCount("test_runs"));
        assertEquals(4, rowCount("test_attempts"));
        assertEquals(4, rowCount("test_run_cases"));
    }

    @Test void mappingLifecycleAuthorizationAndProjectOwnershipStayInServices() throws Exception {
        TestCase first = readyCase("first");
        TestCase second = readyCase("second");
        Project other = projects.create(admin.id(), new CreateProjectCommand("OTHER", "Other", null, null, null));
        TestCase foreign = readyCase(admin, other, "foreign");

        login(developer);
        assertEquals(403, call("POST", "/projects/" + project.id() + "/automation/identities",
                json("{\"source\":\"JUNIT\",\"namespace\":\"example.Life\",\"externalKey\":\"one\"}"),
                "application/json", true, origin()).statusCode());
        assertEquals(403, call("POST", importPath(project.id(), UUID.randomUUID(), "example.Life"),
                xml("example.Life", "<testcase classname=\"example.Life\" name=\"one\"/>"),
                "application/xml", true, origin()).statusCode());
        assertEquals(403, call("POST", previewPath(other.id(), "example.Life"),
                xml("example.Life", "<testcase classname=\"example.Life\" name=\"one\"/>"),
                "application/xml", true, origin()).statusCode());

        login(tester);
        JsonNode identity = expect(201, "POST", "/projects/" + project.id() + "/automation/identities",
                json("{\"source\":\"JUNIT\",\"namespace\":\"example.Life\",\"externalKey\":\"one\"}"),
                "application/json", true, origin());
        long id = identity.get("id").asLong();
        assertEquals(400, call("PUT", "/automation/identities/" + id + "/mapping",
                json("{\"testCaseId\":" + foreign.id() + ",\"expectedVersion\":null}"),
                "application/json", true, origin()).statusCode());
        JsonNode mapped = expect(200, "PUT", "/automation/identities/" + id + "/mapping",
                json("{\"testCaseId\":" + first.id() + ",\"expectedVersion\":null}"),
                "application/json", true, origin());
        assertEquals(409, call("PUT", "/automation/identities/" + id + "/mapping",
                json("{\"testCaseId\":" + second.id() + ",\"expectedVersion\":0}"),
                "application/json", true, origin()).statusCode());
        JsonNode inactive = expect(200, "POST", "/automation/identities/" + id + "/mapping/deactivate",
                json("{\"expectedVersion\":" + mapped.get("version").asInt() + "}"),
                "application/json", true, origin());
        assertEquals("INACTIVE", inactive.get("status").asText());
        assertEquals(404, call("GET", "/automation/identities/" + id + "/mapping",
                null, null, false, null).statusCode());
        JsonNode unmapped = expect(200, "POST", previewPath(project.id(), "example.Life"),
                xml("example.Life", "<testcase classname=\"example.Life\" name=\"one\"/>"),
                "application/xml", true, origin());
        assertEquals(1, unmapped.get("unknownIdentities").size());
        JsonNode rebound = expect(200, "PUT", "/automation/identities/" + id + "/mapping",
                json("{\"testCaseId\":" + second.id() + ",\"expectedVersion\":" + inactive.get("version").asInt() + "}"),
                "application/json", true, origin());
        assertEquals(second.id().longValue(), rebound.get("testCaseId").asLong());
    }

    @Test void transportRejectsUnauthenticatedBadOriginMarkerMalformedXmlDtdAndOversize() throws Exception {
        byte[] report = xml("example.Security", "<testcase classname=\"example.Security\" name=\"one\"/>");
        String preview = previewPath(project.id(), "example.Security");
        assertEquals(401, call("POST", preview, report, "application/xml", true, origin()).statusCode());
        login(tester);
        assertEquals(403, call("POST", preview, report, "application/xml", true,
                "https://evil.example").statusCode());
        assertEquals(403, call("POST", preview, report, "application/xml", false, origin()).statusCode());
        assertEquals(415, call("POST", preview, report, "text/xml", true, origin()).statusCode());
        assertEquals(400, call("POST", preview, "<testsuite><testcase></testsuite>".getBytes(StandardCharsets.UTF_8),
                "application/xml", true, origin()).statusCode());
        byte[] xxe = ("<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///never/read\">]>"
                + "<testsuite><testcase classname=\"example.Security\" name=\"one\"><failure>&xxe;</failure>"
                + "</testcase></testsuite>").getBytes(StandardCharsets.UTF_8);
        assertEquals(400, call("POST", preview, xxe, "application/xml", true, origin()).statusCode());
        assertEquals(413, call("POST", preview, new byte[XmlHttp.MAX_BODY + 1], "application/xml", true,
                origin()).statusCode());
        assertEquals(400, call("POST", preview + "&sourceNamespace=duplicate", report,
                "application/xml", true, origin()).statusCode());
        assertEquals(0, rowCount("test_runs"));
        assertEquals(0, rowCount("test_imports"));
    }

    private User account(String username, SystemRole role) {
        return tx.inTransaction(connection -> new JdbcUserDao(connection).insert(new User(null, username, username,
                passwordHash, role, UserStatus.ACTIVE, null, null, null)));
    }

    private TestCase readyCase(String title) { return readyCase(tester, project, title); }

    private TestCase readyCase(User actor, Project owner, String title) {
        TestCase draft = testCases.create(actor.id(), new CreateTestCaseCommand(owner.id(), title, "description",
                "precondition", Priority.MEDIUM, List.of(new TestStepInput(1, "action", "expected"))));
        return testCases.update(actor.id(), new UpdateTestCaseCommand(draft.id(), draft.title(), draft.description(),
                draft.preconditions(), draft.priority(), TestCaseStatus.READY, draft.lockVersion(),
                List.of(new TestStepInput(1, "action", "expected"))));
    }

    private void login(User user) throws Exception {
        cookie = null;
        HttpResponse<String> http = call("POST", "/auth/login",
                json("{\"username\":\"" + user.username() + "\",\"password\":\"round4-test-password\"}"),
                "application/json", true, origin());
        assertEquals(200, http.statusCode(), http.body());
        JsonNode response = parse(http.body());
        assertEquals(user.id().longValue(), response.get("id").asLong());
        cookie = http.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> call(String method, String path, byte[] body, String contentType,
                                      boolean marker, String requestOrigin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.base() + "/api" + path))
                .timeout(Duration.ofSeconds(20));
        if (requestOrigin != null) request.header("Origin", requestOrigin);
        if (marker) request.header("X-Veriqra-Request", "1");
        if (cookie != null) request.header("Cookie", cookie);
        if (contentType != null) request.header("Content-Type", contentType);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(int status, String method, String path, byte[] body, String contentType,
                            boolean marker, String requestOrigin) throws Exception {
        HttpResponse<String> response = call(method, path, body, contentType, marker, requestOrigin);
        assertEquals(status, response.statusCode(), method + " " + path + ": " + response.body());
        if (status == 204) return null;
        return parse(response.body());
    }

    private JsonNode parse(String value) throws Exception {
        return JsonMapperProvider.readerFor(JsonNode.class).readValue(value);
    }

    private String origin() { return "http://" + URI.create(server.base()).getRawAuthority(); }

    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String previewPath(long projectId, String namespace) {
        return "/projects/" + projectId + "/imports/preview?sourceNamespace=" + encode(namespace);
    }

    private static String importPath(long projectId, UUID key, String namespace) {
        return "/projects/" + projectId + "/imports?requestKey=" + key
                + "&sourceNamespace=" + encode(namespace) + "&originalFilename=report.xml&runName=JUnit%20report"
                + "&environment=test&buildVersion=1.0";
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static byte[] xml(String namespace, String... entries) {
        return ("<testsuite name=\"" + namespace + "\">" + String.join("", entries) + "</testsuite>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private long rowCount(String table) {
        if (!Set.of("test_automation_identities", "test_runs", "test_imports", "test_attempts",
                "test_run_cases").contains(table)) throw new IllegalArgumentException("Unexpected table");
        return tx.inTransaction(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getLong(1);
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
        });
    }
}
