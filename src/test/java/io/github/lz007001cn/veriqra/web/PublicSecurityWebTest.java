package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.auth.AuthenticatedUser;
import io.github.lz007001cn.veriqra.service.exception.AuthenticationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicSecurityWebTest {
    @TempDir static Path dir;
    EmbeddedWebServer server, secureServer, rootServer;
    HttpClient client;
    @BeforeAll void start() throws Exception {
        AuthService auth=new AuthService() {
            public AuthenticatedUser authenticate(String name,String password) {
                if (!("valid".equals(name) || "reset".equals(name)) || !"correct".equals(password)) throw new AuthenticationException();
                return current(1L);
            }
            public AuthenticatedUser current(Long id) { return new AuthenticatedUser(id,"valid",SystemRole.ADMIN,UserStatus.ACTIVE); }
        };
        var services=new WebServices(auth,unused(ProjectService.class),unused(RequirementService.class),unused(TestCaseService.class),
                unused(TraceabilityService.class),unused(TestPlanService.class),unused(TestRunService.class),
                unused(TestExecutionService.class),unused(DefectService.class),unused(AutomationService.class),
                unused(TestImportService.class));
        server=new EmbeddedWebServer(dir.resolve("local"),services);
        secureServer=new EmbeddedWebServer(dir.resolve("secure"),services,true);
        rootServer=new EmbeddedWebServer(dir.resolve("root"),services,false, "");
        client=HttpClient.newHttpClient();
    }
    static <T> T unused(Class<T> type) { return WebFoundationTest.proxy(type,(o,m,a)->{throw new AssertionError("Security gate bypassed");}); }
    @AfterAll void stop() throws Exception { if(rootServer!=null)rootServer.close(); if(client!=null)client.close(); if(server!=null)server.close(); if(secureServer!=null)secureServer.close(); }
    String origin(EmbeddedWebServer s) { return "http://"+URI.create(s.base()).getRawAuthority(); }
    HttpResponse<String> send(EmbeddedWebServer s,String method,String path,String body,String origin,boolean marker, String cookie) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(s.base()+"/api"+path));
        if(origin!=null)b.header("Origin",origin);
        if(marker)b.header("X-Veriqra-Request","1");
        if(cookie!=null)b.header("Cookie",cookie);
        // Untrusted forwarding headers must not influence IP quotas or Origin verification.
        b.header("X-Forwarded-For",UUID.randomUUID().toString()).header("X-Forwarded-Proto","https");
        if(body!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String name,String password) throws Exception {
        return send(server,"POST","/auth/login","{\"username\":\""+name+"\",\"password\":\""+password+"\"}",origin(server),true,null);
    }
    @Test void failuresYieldSafe429DespiteForgedForwardedIp() throws Exception {
        for(int i=0;i<5;i++)assertEquals(401,login("locked","wrong").statusCode());
        var r=login("locked","wrong"); assertEquals(429,r.statusCode());
        assertTrue(Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow())>0);
        assertFalse(r.body().contains("locked")); assertFalse(r.body().contains("wrong"));
        assertFalse(r.body().contains("failures")); assertTrue(r.headers().firstValue("set-cookie").isEmpty());
    }
    @Test void successResetsPairFailureBudget() throws Exception {
        for(int i=0;i<4;i++)assertEquals(401,login("reset","wrong").statusCode());
        assertEquals(200,login("reset","correct").statusCode());
        for(int i=0;i<4;i++)assertEquals(401,login("reset","wrong").statusCode());
        assertEquals(200,login("reset","correct").statusCode());
    }
    @Test void everyWriteRouteUsesOriginGateBeforeBusinessLogic() throws Exception {
        var r=login("valid","correct"); assertEquals(200,r.statusCode());
        String cookie=r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
        for(String path:List.of("/auth/login","/auth/logout","/projects","/projects/1/requirements","/projects/1/requirements/1",
                "/projects/1/requirements/1/test-cases/1","/projects/1/requirements/1/test-cases/1/confirm","/projects/1/requirements/1/test-cases/1/remove",
                "/projects/1/test-cases","/projects/1/test-cases/1","/projects/1/test-plans","/projects/1/test-plans/1",
                "/projects/1/test-plans/1/archive","/projects/1/test-plans/1/test-cases/1",
                "/projects/1/test-plans/1/test-cases/1/remove","/projects/1/runs","/projects/1/runs/1/complete","/projects/1/runs/1/cancel",
                "/projects/1/runs/1/cases/1/attempts","/projects/1/defects","/projects/1/defects/1",
                "/projects/1/defects/1/start","/projects/1/defects/1/resolve","/projects/1/defects/1/close",
                "/projects/1/defects/1/reopen","/projects/1/defects/1/evidence","/projects/1/defects/1/evidence/1/remove",
                "/projects/1/automation/identities","/automation/identities/1/mapping",
                "/automation/identities/1/mapping/deactivate","/projects/1/imports/preview","/projects/1/imports")) {
            for(String method:List.of("POST","PUT","PATCH","DELETE"))
                assertEquals(403,send(server,method,path,"{}","https://evil.example",true,cookie).statusCode(),path);
        }
    }
    @Test void missingOpaqueOriginAndMissingMarkerRejectButGetNeedsNeither() throws Exception {
        for(String o:Arrays.asList(null,"null","https://evil.example"))
            assertEquals(403,send(server,"POST","/auth/login","{}",o,true,null).statusCode());
        assertEquals(403,send(server,"POST","/auth/login","{}",origin(server),false,null).statusCode());
        var get=send(server,"GET","/auth/me",null,null,false,null);
        assertEquals(401,get.statusCode()); assertTrue(get.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
    @Test void secureAndLocalCookiesHaveCorrectAttributes() throws Exception {
        for(var s:List.of(server,secureServer,rootServer)) {
            var r=send(s,"POST","/auth/login","{\"username\":\"valid\",\"password\":\"correct\"}",origin(s),true,null);
            assertEquals(200,r.statusCode());
            String cookie=r.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(cookie.contains(s==rootServer ? "Path=/;" : "Path=/veriqra;")); assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
            assertEquals(s==secureServer,cookie.contains("Secure"));
        }
    }
    @Test void rootContextSupportsLoginIdentityAndLogout() throws Exception {
        var b=HttpRequest.newBuilder(URI.create(rootServer.base()+"/api/auth/login"))
                .header("Origin",origin(rootServer)).header("X-Veriqra-Request","1")
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"valid\",\"password\":\"correct\"}"));
        var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode());
        String cookie=r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
        assertEquals(200,send(rootServer,"GET","/auth/me",null,null,false,cookie).statusCode());
        assertEquals(403,send(rootServer,"POST","/auth/logout",null,"https://evil.example",true,cookie).statusCode());
        assertEquals(204,send(rootServer,"POST","/auth/logout",null,origin(rootServer),true,cookie).statusCode());
        assertEquals(401,send(rootServer,"GET","/auth/me",null,null,false,cookie).statusCode());
    }
}
