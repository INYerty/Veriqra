package io.github.lz007001cn.veriqra.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lz007001cn.veriqra.admin.*;
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
import java.util.UUID;
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
        JsonNode taskPage = expect(200, "GET", root + "/tasks?page=1&pageSize=25&status=OPEN", null);
        assertEquals(1, taskPage.get("total").asInt());
        assertEquals(1, taskPage.get("items").size());
        assertEquals(taskId, taskPage.get("items").get(0).get("id").asLong());
        assertEquals(0, expect(200, "GET", root + "/tasks?page=2&pageSize=25", null).get("items").size());
        expect(400, "GET", root + "/tasks?page=0", null);
        expect(400, "GET", root + "/tasks?pageSize=101", null);
        expect(400, "GET", root + "/tasks?status=INVALID", null);
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

    @Test void creditAndHandoffRoutesKeepProjectScopeAndSerializeBigintAsText() throws Exception {
        User admin = account("http_credit_admin", SystemRole.ADMIN);
        User manager = account("http_credit_manager", SystemRole.USER);
        User sender = account("http_credit_sender", SystemRole.USER);
        User recipient = account("http_credit_recipient", SystemRole.USER);
        User outsider = account("http_credit_outsider", SystemRole.USER);
        Project project = createProject(admin, "HTTPCREDIT", manager, ProjectRole.TESTER);
        addMember(project, sender, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        addMember(project, recipient, ProjectRole.TESTER, MembershipStatus.ACTIVE);
        collaboration.appointManager(admin.id(), project.id(), manager.id());
        ProjectTeam team = collaboration.createTeam(manager.id(), project.id(), "HTTP credit team", manager.id());
        collaboration.setTeamMember(manager.id(), project.id(), team.id(), sender.id(), MembershipStatus.ACTIVE);
        collaboration.setTeamMember(manager.id(), project.id(), team.id(), recipient.id(), MembershipStatus.ACTIVE);
        tx.inTransaction(c -> { var credit = new JdbcCreditDao(c);
            for (User user : java.util.List.of(admin, manager, sender, recipient, outsider)) credit.createAccount(user.id());
            return null; });
        new CreditService(serviceTx, new AdminAccessPolicy()).grant(
                new AdminContext(admin.id(), "127.0.0.1", UUID.randomUUID().toString()), sender.id(), 100, "fixture");
        String root = "/projects/" + project.id();
        login(sender);
        assertEquals("100", expect(200, "GET", "/credits/me", null).get("balance").asText());
        String key = UUID.randomUUID().toString();
        String payload = "{\"recipientUserId\":" + recipient.id() + ",\"amount\":\"7\",\"operationId\":\"" + key + "\"}";
        JsonNode transfer = expect(201, "POST", root + "/credit-transfers", payload);
        assertEquals("7", transfer.get("amount").asText());
        expect(201, "POST", root + "/credit-transfers", payload);
        assertEquals("93", expect(200, "GET", "/credits/me", null).get("balance").asText());
        expect(403, "POST", root + "/credit-transfers", "{\"recipientUserId\":" + outsider.id()
                + ",\"amount\":\"1\",\"operationId\":\"" + UUID.randomUUID() + "\"}");
        login(manager);
        JsonNode task = expect(201, "POST", root + "/tasks", "{\"teamId\":" + team.id()
                + ",\"title\":\"HTTP handoff\",\"assigneeUserId\":" + sender.id() + ",\"rewardCredit\":\"20\"}");
        assertEquals("20", task.get("rewardCredit").asText());
        login(sender);
        JsonNode offer = expect(201, "POST", root + "/handoffs", "{\"taskId\":" + task.get("id").asLong()
                + ",\"recipientUserId\":" + recipient.id() + ",\"amount\":\"5\",\"operationId\":\"" + UUID.randomUUID() + "\"}");
        expect(403, "POST", root + "/handoffs/" + offer.get("id").asLong() + "/accept", "{}");
        login(recipient);
        expect(200, "POST", root + "/handoffs/" + offer.get("id").asLong() + "/accept", "{}");
        assertEquals("12", expect(200, "GET", "/credits/me", null).get("balance").asText());
        assertEquals(recipient.id().longValue(), expect(200, "GET", root + "/tasks/" + task.get("id").asLong(), null)
                .get("task").get("assigneeUserId").asLong());
        login(outsider);
        expect(403, "GET", root + "/contributions?month=2030-01", null);
    }
}
