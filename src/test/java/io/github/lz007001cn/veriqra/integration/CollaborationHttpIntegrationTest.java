package io.github.lz007001cn.veriqra.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.DefaultAuthService;
import io.github.lz007001cn.veriqra.service.auth.PasswordVerifier;
import io.github.lz007001cn.veriqra.web.*;
import io.github.lz007001cn.veriqra.web.json.JsonMapperProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP -> scoped Service -> isolated MySQL; no development database or seed. */
class CollaborationHttpIntegrationTest extends ServiceFixture {
    @TempDir Path directory;
    EmbeddedWebServer server;
    HttpClient client;
    String cookie;
    String hash;

    @BeforeEach void startHttp() throws Exception {
        hash = new PasswordVerifier().hash("collaboration-test-password");
        server = new EmbeddedWebServer(directory, new WebServices(
                new DefaultAuthService(serviceTx, jdbcDaos, new PasswordVerifier()), projects, requirements,
                testCases, traceability, testPlans, testRuns, execution, defects, automation, imports),
                collaboration, false, "");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void stopHttp() throws Exception { if (server != null) server.close(); }

    private User account(String username, SystemRole role) {
        return tx.inTransaction(c -> new JdbcUserDao(c).insert(new User(null, username, username,
                hash, role, UserStatus.ACTIVE, null, null, null)));
    }
    private HttpResponse<String> call(String method, String resource, String body) throws Exception {
        var address = URI.create(server.base() + "/api" + resource);
        var request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15))
                .header("Origin", "http://" + address.getRawAuthority())
                .header("X-Veriqra-Request", "1");
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode expect(int status, String method, String resource, String body) throws Exception {
        var result = call(method, resource, body);
        assertEquals(status, result.statusCode(), result.body());
        return status == 204 ? null : JsonMapperProvider.readerFor(JsonNode.class).readValue(result.body());
    }
    private void login(User user) throws Exception {
        var response = call("POST", "/auth/login", "{\"username\":\"" + user.username()
                + "\",\"password\":\"collaboration-test-password\"}");
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    @Test void scopedAppointmentTeamAssignmentSubmissionAndIndependentAcceptanceOverHttp() throws Exception {
        User admin = account("http_collab_admin", SystemRole.ADMIN);
        User manager = account("http_collab_manager", SystemRole.USER);
        User lead = account("http_collab_lead", SystemRole.USER);
        User worker = account("http_collab_worker", SystemRole.USER);
        Project project = createProject(admin, "HTTPCOLLAB", manager, ProjectRole.TESTER);
        addMember(project, lead, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, worker, ProjectRole.DEVELOPER, MembershipStatus.ACTIVE);
        String root = "/projects/" + project.id();

        login(manager);
        expect(403, "POST", root + "/managers", "{\"userId\":" + manager.id() + "}");
        expect(403, "POST", root + "/teams", "{\"name\":\"Denied\",\"leadUserId\":" + lead.id() + "}");
        login(admin);
        expect(201, "POST", root + "/managers", "{\"userId\":" + manager.id() + "}");
        login(manager);
        JsonNode team = expect(201, "POST", root + "/teams", "{\"name\":\"Login\",\"leadUserId\":" + lead.id() + "}");
        long teamId = team.get("id").asLong();
        login(lead);
        expect(200, "POST", root + "/teams/" + teamId + "/members",
                "{\"userId\":" + worker.id() + ",\"status\":\"ACTIVE\"}");
        JsonNode task = expect(201, "POST", root + "/tasks", "{\"teamId\":" + teamId
                + ",\"title\":\"Verify login\",\"assigneeUserId\":" + worker.id() + "}");
        long taskId = task.get("id").asLong();
        String taskPath = root + "/tasks/" + taskId;
        login(worker);
        expect(403, "POST", root + "/teams", "{\"name\":\"Denied\",\"leadUserId\":" + worker.id() + "}");
        JsonNode started = expect(200, "POST", taskPath + "/status",
                "{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}");
        JsonNode submitted = expect(200, "POST", taskPath + "/status",
                "{\"status\":\"SUBMITTED\",\"expectedVersion\":" + started.get("lockVersion").asInt() + "}");
        expect(403, "POST", taskPath + "/status", "{\"status\":\"ACCEPTED\",\"expectedVersion\":"
                + submitted.get("lockVersion").asInt() + "}");
        login(lead);
        JsonNode accepted = expect(200, "POST", taskPath + "/status", "{\"status\":\"ACCEPTED\",\"expectedVersion\":"
                + submitted.get("lockVersion").asInt() + "}");
        assertEquals("ACCEPTED", accepted.get("status").asText());
        assertEquals(4, expect(200, "GET", taskPath, null).get("events").size());
    }
}
