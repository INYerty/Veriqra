package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.auth.*;
import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.exception.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFoundationTest {
    @TempDir static Path directory;
    EmbeddedWebServer server;
    HttpClient client;
    String cookie;
    final AtomicReference<Throwable> nextFailure = new AtomicReference<>();
    boolean active;
    Long actualActor;
    static final LocalDateTime TIME = LocalDateTime.of(2026, 9, 20, 10, 0, 0, 123456000);
    final Project project = new Project(7L, "QA", "质量项目", null, ProjectStatus.ACTIVE, 1L, TIME, TIME, 0);

    @BeforeAll void start() throws Exception {
        AuthService auth = new AuthService() {
            public AuthenticatedUser authenticate(String username, String password) {
                if (!active || !"tester".equals(username) || !"test-only-password".equals(password)) throw new AuthenticationException();
                return current(1L);
            }
            public AuthenticatedUser current(Long actor) {
                if (!active || !Objects.equals(actor, 1L)) throw new AuthenticationException();
                return new AuthenticatedUser(actor, "tester", SystemRole.USER, UserStatus.ACTIVE);
            }
        };
        ProjectService projects = proxy(ProjectService.class, (object, method, args) -> {
            Throwable failure = nextFailure.getAndSet(null);
            if (failure != null) throw failure;
            actualActor = (Long) args[0];
            if (method.getName().equals("list")) return List.of(project);
            return project;
        });
        RequirementService requirements = proxy(RequirementService.class, (object, method, args) -> {
            actualActor = (Long) args[0];
            Requirement requirement = new Requirement(9L, 7L, 1L, "中文需求", null, Priority.HIGH,
                    RequirementStatus.DRAFT, actualActor, TIME, TIME, 0);
            return method.getName().equals("listByProject") ? List.of(requirement) : requirement;
        });
        server = new EmbeddedWebServer(directory, new WebServices(auth, projects, requirements,
                proxy(TestCaseService.class, (o, m, a) -> { throw new AssertionError("Unexpected test-case call"); }),
                proxy(TraceabilityService.class, (o, m, a) -> { throw new AssertionError("Unexpected traceability call"); }),
                proxy(TestPlanService.class, (o, m, a) -> { throw new AssertionError("Unexpected plan call"); }),
                WebFoundationTest.proxy(TestRunService.class, (o,m,a) -> { throw new AssertionError("Unexpected Round 3 call"); }),
                WebFoundationTest.proxy(TestExecutionService.class, (o,m,a) -> { throw new AssertionError("Unexpected Round 3 call"); }),
                WebFoundationTest.proxy(DefectService.class, (o,m,a) -> { throw new AssertionError("Unexpected Round 3 call"); }),
                WebFoundationTest.proxy(AutomationService.class, (o,m,a) -> { throw new AssertionError("Unexpected Round 4 call"); }),
                WebFoundationTest.proxy(TestImportService.class, (o,m,a) -> { throw new AssertionError("Unexpected Round 4 call"); })));
        client = HttpClient.newHttpClient();
    }
    @AfterAll void stop() throws Exception { if (server != null) server.close(); }
    @BeforeEach void reset() { active = true; cookie = null; nextFailure.set(null); actualActor = null; }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    HttpResponse<String> call(String method, String path, String body, String contentType, boolean marker) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.base() + "/api" + path));
        request.header("Origin", URI.create(server.base()).resolve("/").toString().replaceAll("/$", ""));
        if (cookie != null) request.header("Cookie", cookie);
        if (contentType != null) request.header("Content-Type", contentType);
        if (marker) request.header("X-Veriqra-Request", "1");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login() throws Exception {
        var response = call("POST", "/auth/login", "{\"username\":\"tester\",\"password\":\"test-only-password\"}", "application/json", true);
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        return response;
    }

    @Test void loginMeLogoutAndOldSessionRejected() throws Exception {
        var response = login();
        assertTrue(response.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly"));
        assertTrue(response.headers().firstValue("set-cookie").orElseThrow().contains("SameSite=Lax"));
        assertFalse(response.body().contains("password"));
        assertEquals(200, call("GET", "/auth/me", null, null, false).statusCode());
        String old = cookie;
        assertEquals(204, call("POST", "/auth/logout", null, null, true).statusCode());
        cookie = old;
        assertEquals(401, call("GET", "/auth/me", null, null, false).statusCode());
    }

    @Test void loginRotatesExistingSessionAndUrlSessionIdIsNotAccepted() throws Exception {
        login(); String previous = cookie;
        login(); assertNotEquals(previous, cookie);
        String current = cookie;
        cookie = previous;
        assertEquals(401, call("GET", "/auth/me", null, null, false).statusCode());
        cookie = null;
        String id = current.substring(current.indexOf('=') + 1);
        assertEquals(401, call("GET", "/auth/me;jsessionid=" + id, null, null, false).statusCode());
    }

    @Test void protectedRequestRequiresSessionAndRechecksDisabledUser() throws Exception {
        assertEquals(401, call("GET", "/projects", null, null, false).statusCode());
        assertEquals(401, call("GET", "/auth/me", null, null, false).statusCode());
        login(); active = false;
        assertEquals(401, call("GET", "/projects", null, null, false).statusCode());
        active = true;
        assertEquals(401, call("GET", "/auth/me", null, null, false).statusCode());
    }

    @Test void invalidCredentialsAndDisabledLoginAreGeneric() throws Exception {
        var bad = call("POST", "/auth/login", "{\"username\":\"tester\",\"password\":\"wrong-secret\"}", "application/json", true);
        assertEquals(401, bad.statusCode()); assertFalse(bad.body().contains("wrong-secret"));
        active = false;
        var disabled = call("POST", "/auth/login", "{\"username\":\"tester\",\"password\":\"test-only-password\"}", "application/json", true);
        assertEquals(401, disabled.statusCode()); assertEquals(bad.body(), disabled.body());
    }

    @Test void csrfHeaderRequiredEvenForLoginAndLogout() throws Exception {
        assertEquals(403, call("POST", "/auth/login", "{}", "application/json", false).statusCode());
        login();
        assertEquals(403, call("POST", "/auth/logout", null, null, false).statusCode());
        assertEquals(200, call("GET", "/auth/me", null, null, false).statusCode());
        var preflight = call("OPTIONS", "/projects", null, null, false);
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    static Stream<Arguments> errors() {
        return Stream.of(Arguments.of(new ValidationException("unsafe"), 400, "VALIDATION"),
                Arguments.of(new NotFoundException("unsafe"), 404, "NOT_FOUND"),
                Arguments.of(new ForbiddenException("unsafe"), 403, "FORBIDDEN"),
                Arguments.of(new ConflictException("unsafe"), 409, "CONFLICT"),
                Arguments.of(new DataAccessException("SQL secret", new SQLException("password_hash secret")), 500, "INTERNAL_ERROR"),
                Arguments.of(new IllegalStateException("secret"), 500, "INTERNAL_ERROR"),
                Arguments.of(new AssertionError("secret"), 500, "INTERNAL_ERROR"));
    }
    @ParameterizedTest @MethodSource("errors")
    void safeExceptionMapping(Throwable failure, int status, String code) throws Exception {
        login(); nextFailure.set(failure);
        var response = call("GET", "/projects", null, null, false);
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains(code));
        assertFalse(response.body().contains("secret")); assertFalse(response.body().contains("unsafe"));
        assertFalse(response.body().contains("SQLException")); assertFalse(response.body().contains("stackTrace"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{\"username\":123}", "{\"username\":true}",
            "{\"username\":\"a\",\"actorUserId\":1}", "{\"systemRole\":\"ADMIN\"}",
            "{\"username\":\"a\",\"username\":\"b\"}", "{}{}"})
    void invalidJsonAndForgedIdentityAreRejected(String body) throws Exception {
        assertEquals(400, call("POST", "/auth/login", body, "application/json", true).statusCode());
    }

    @Test void mediaTypeSizeAndMethodsAreBounded() throws Exception {
        assertEquals(415, call("POST", "/auth/login", "{}", "text/plain", true).statusCode());
        assertEquals(413, call("POST", "/auth/login", " ".repeat(65537), "application/json", true).statusCode());
        assertEquals(405, call("GET", "/auth/login", null, null, false).statusCode());
        login();
        var response = call("DELETE", "/projects/7", null, null, true);
        assertEquals(405, response.statusCode()); assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
        assertEquals(400, call("GET", "/projects/abc", null, null, false).statusCode());
        assertEquals(404, call("GET", "/unknown", null, null, false).statusCode());
    }

    @Test void projectAndRequirementRoutesUseSessionActorAndUtf8Dtos() throws Exception {
        login();
        var list = call("GET", "/projects", null, null, false);
        assertEquals(200, list.statusCode()); assertTrue(list.body().contains("质量项目"));
        assertTrue(list.headers().firstValue("content-type").orElseThrow().toLowerCase().contains("utf-8"));
        assertTrue(list.body().contains("2026-09-20T10:00:00.123456"));
        assertFalse(list.body().contains("lockVersion"));
        assertEquals(200, call("GET", "/projects/7", null, null, false).statusCode());
        assertEquals(201, call("POST", "/projects", "{\"projectKey\":\"QA\",\"name\":\"质量项目\"}", "application/json", true).statusCode());
        assertEquals(1L, actualActor);
        actualActor = null;
        assertEquals(400, call("POST", "/projects", "{\"projectKey\":\"QA\",\"name\":\"x\",\"actorUserId\":999}", "application/json", true).statusCode());
        assertNull(actualActor);
        assertEquals(200, call("GET", "/projects/7/requirements", null, null, false).statusCode());
        assertEquals(201, call("POST", "/projects/7/requirements", "{\"title\":\"中文需求\",\"priority\":\"HIGH\"}", "application/json", true).statusCode());
        assertEquals(1L, actualActor);
        assertEquals(400, call("POST", "/projects/7/requirements", "{\"title\":\"a\",\"priority\":1}", "application/json", true).statusCode());
    }
}
