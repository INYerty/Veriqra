package io.github.lz007001cn.veriqra.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.auth.PasswordVerifier;
import io.github.lz007001cn.veriqra.service.support.*;
import io.github.lz007001cn.veriqra.web.*;
import io.github.lz007001cn.veriqra.web.json.JsonMapperProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Core assets are created via HTTP; only users/members are bootstrapped using DAOs. */
class TestAssetHttpIntegrationTest extends MysqlFixture {
    @TempDir static Path directory;
    EmbeddedWebServer server;
    HttpClient client;
    String cookie;
    String hash;
    User admin, tester, developer;
    ProjectService projectService;

    @BeforeAll void startHttp() throws Exception {
        // No DatabaseConfig.load/application environment: reuse the already checked fixture pool exclusively.
        if (!config.jdbcUrl().matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/veriqra_test_[a-z0-9_]+"))
            throw new IllegalStateException("Asset HTTP tests require the isolated 13307 fixture");
        var transactions = new JdbcServiceTransaction(tx);
        var daos = new JdbcServiceDaoFactory();
        var access = new ProjectAccessPolicy();
        projectService = new DefaultProjectService(transactions, daos, access);
        server = new EmbeddedWebServer(directory, new WebServices(
                new DefaultAuthService(transactions, daos, new PasswordVerifier()), projectService,
                new DefaultRequirementService(transactions, daos, access),
                new DefaultTestCaseService(transactions, daos, access),
                new DefaultTraceabilityService(transactions, daos, access, Clock.systemUTC()),
                new DefaultTestPlanService(transactions, daos, access),
                new DefaultTestRunService(transactions, daos, access, Clock.systemUTC()),
                new DefaultTestExecutionService(transactions, daos, access, Clock.systemUTC()),
                new DefaultDefectService(transactions, daos, access)), false, "");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec("asset-test-password".toCharArray(),salt,600000,256);
        try {
            hash="pbkdf2_sha256$600000$"+HexFormat.of().formatHex(salt)+"$"+
                    HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } finally { spec.clearPassword(); }
    }
    @AfterAll void stopHttp() throws Exception { if(server!=null)server.close(); }
    @BeforeEach void bootstrapAccounts() {
        cookie=null;
        admin=account("asset_admin",SystemRole.ADMIN);
        tester=account("asset_tester",SystemRole.USER);
        developer=account("asset_developer",SystemRole.USER);
    }
    User account(String name,SystemRole role) {
        return tx.inTransaction(c->new JdbcUserDao(c).insert(new User(null,name,name,hash,role,UserStatus.ACTIVE,null,null,null)));
    }
    void member(long project,User user,ProjectRole role,MembershipStatus status) {
        tx.inTransaction(c-> {
            var dao=new JdbcProjectMemberDao(c);
            var existing=dao.find(project,user.id());
            if(existing.isEmpty())dao.add(new ProjectMember(project,user.id(),role,status,null,null,null));
            else {var v=existing.get();dao.update(new ProjectMember(project,user.id(),role,status,v.joinedAt(),v.updatedAt(),v.lockVersion()));}
            return null;
        });
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server.base()+"/api"+path)).timeout(Duration.ofSeconds(15))
                .header("X-Veriqra-Request","1");
        request.header("Origin", "http://" + URI.create(server.base()).getRawAuthority());
        if(cookie!=null)request.header("Cookie",cookie);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode expect(int status,String method,String path,String body) throws Exception {
        var response=call(method,path,body);
        assertEquals(status,response.statusCode(),method+" "+path+": "+response.body());
        if(status==204){assertEquals("",response.body());return null;}
        return JsonMapperProvider.readerFor(JsonNode.class).readValue(response.body());
    }
    void login(User user) throws Exception {
        var r=call("POST","/auth/login","{\"username\":\""+user.username()+"\",\"password\":\"asset-test-password\"}");
        assertEquals(200,r.statusCode(),r.body());
        cookie=r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
    }
    long project(String key) throws Exception {
        return expect(201,"POST","/projects","{\"projectKey\":\""+key+"\",\"name\":\""+key+"\"}").get("id").asLong();
    }
    static String base(long p){return "/projects/"+p;}
    JsonNode requirement(long p) throws Exception {
        return expect(201,"POST",base(p)+"/requirements","{\"title\":\"requirement\",\"description\":\"text\",\"priority\":\"HIGH\"}");
    }
    static String steps(String action) {
        return "[{\"stepOrder\":1,\"action\":\""+action+"\",\"expectedResult\":\"ok\"}]";
    }
    JsonNode testCase(long p) throws Exception {
        return expect(201,"POST",base(p)+"/test-cases","{\"title\":\"case\",\"priority\":\"HIGH\",\"steps\":"+steps("do")+"}");
    }
    static String caseUpdate(int version,String action,String status) {
        return "{\"title\":\"case\",\"priority\":\"HIGH\",\"status\":\""+status+"\",\"expectedVersion\":"+version+",\"steps\":"+steps(action)+"}";
    }
    static String requirementUpdate(int version,String title) {
        return "{\"title\":\""+title+"\",\"description\":\"text\",\"priority\":\"HIGH\",\"status\":\"DRAFT\",\"expectedVersion\":"+version+"}";
    }
    static String planUpdate(int version,String status) {
        return "{\"name\":\"plan\",\"status\":\""+status+"\",\"expectedVersion\":"+version+"}";
    }
    JsonNode plan(long p,List<Long> cases) throws Exception {
        return expect(201,"POST",base(p)+"/test-plans","{\"name\":\"plan\",\"testCaseIds\":"+JsonMapperProvider.writer().writeValueAsString(cases)+"}");
    }
    static String version(int v){return "{\"expectedVersion\":"+v+"}";}
    String link(long p,long r,long c){return base(p)+"/requirements/"+r+"/test-cases/"+c;}
    String state(long p,long r) throws Exception {
        return expect(200,"GET",base(p)+"/requirements/"+r+"/test-cases",null).get(0).get("status").asText();
    }

    @Test void completeHttpAssetWorkflowPersistsReadyPlanAndConfirmedLink() throws Exception {
        login(admin);long p=project("CHAIN");
        long r=requirement(p).get("id").asLong();
        var draft=testCase(p);long c=draft.get("id").asLong();
        expect(200,"PUT",base(p)+"/test-cases/"+c,caseUpdate(draft.get("version").asInt(),"do","READY"));
        expect(204,"POST",link(p,r,c),"{}");
        expect(204,"POST",link(p,r,c)+"/confirm","{}");
        var plan=plan(p,List.of(c));long planId=plan.get("id").asLong();
        var ready=expect(200,"PUT",base(p)+"/test-plans/"+planId,planUpdate(plan.get("version").asInt(),"READY"));
        assertEquals("READY",ready.get("status").asText());
        assertEquals(c,expect(200,"GET",base(p)+"/test-plans/"+planId,null).at("/testCaseIds/0").asLong());
        assertEquals(1,expect(200,"GET",base(p)+"/test-plans",null).size());
        assertEquals(1,expect(200,"GET",base(p)+"/test-cases",null).size());
        tx.inTransaction(connection-> {
            assertEquals(TestPlanStatus.READY,new JdbcTestPlanDao(connection).findById(planId).orElseThrow().status());
            assertEquals(TraceabilityStatus.CONFIRMED,new JdbcTestCaseRequirementDao(connection).find(r,c).orElseThrow().status());
            assertEquals("do",new JdbcTestStepDao(connection).listByTestCase(c).getFirst().action());
            assertEquals(1,new JdbcTestPlanCaseDao(connection).listByTestPlan(planId).size());
            return null;
        });
    }

    @Test void requirementMaterialChangeInvalidatesAndStaleUpdateDoesNotOverwrite() throws Exception {
        login(admin);long p=project("REQ");
        var req=requirement(p);long r=req.get("id").asLong();int v=req.get("version").asInt();
        long c=testCase(p).get("id").asLong();
        expect(204,"POST",link(p,r,c),"{}");expect(204,"POST",link(p,r,c)+"/confirm","{}");
        var updated=expect(200,"PUT",base(p)+"/requirements/"+r,requirementUpdate(v,"changed"));
        assertEquals(v+1,updated.get("version").asInt());assertEquals("NEEDS_REVIEW",state(p,r));
        expect(409,"PUT",base(p)+"/requirements/"+r,requirementUpdate(v,"stale"));
        assertEquals("changed",expect(200,"GET",base(p)+"/requirements/"+r,null).get("title").asText());
    }

    @Test void caseStepReplacementDraftInvalidationAndValidationAreAtomic() throws Exception {
        login(admin);long p=project("CASE");long r=requirement(p).get("id").asLong();
        var draft=testCase(p);long c=draft.get("id").asLong();int v=draft.get("version").asInt();
        var ready=expect(200,"PUT",base(p)+"/test-cases/"+c,caseUpdate(v,"do","READY"));v=ready.get("version").asInt();
        expect(204,"POST",link(p,r,c),"{}");expect(204,"POST",link(p,r,c)+"/confirm","{}");
        var changed=expect(200,"PUT",base(p)+"/test-cases/"+c,caseUpdate(v,"new action","READY"));
        assertEquals("DRAFT",changed.get("status").asText());assertEquals("NEEDS_REVIEW",state(p,r));
        expect(409,"PUT",base(p)+"/test-cases/"+c,caseUpdate(v,"stale","DRAFT"));
        int current=changed.get("version").asInt();
        expect(400,"PUT",base(p)+"/test-cases/"+c,caseUpdate(current,"bad","DRAFT").replace("\"stepOrder\":1","\"stepOrder\":2"));
        expect(400,"PUT",base(p)+"/test-cases/"+c,caseUpdate(current,"bad","READY").replace(steps("bad"),"[]"));
        var detail=expect(200,"GET",base(p)+"/test-cases/"+c,null);
        assertEquals(current,detail.at("/testCase/version").asInt());
        assertEquals("new action",detail.at("/steps/0/action").asText());
    }

    @Test void traceabilityRemoveReattachAndCrossProjectRejection() throws Exception {
        login(admin);long p=project("TRACE");long q=project("OTHER");
        long r=requirement(p).get("id").asLong(),c=testCase(p).get("id").asLong(),foreign=testCase(q).get("id").asLong();
        expect(204,"POST",link(p,r,c),"{}");assertEquals("NEEDS_REVIEW",state(p,r));
        expect(204,"POST",link(p,r,c)+"/confirm","{}");assertEquals("CONFIRMED",state(p,r));
        expect(204,"POST",link(p,r,c)+"/remove","{}");assertEquals("REMOVED",state(p,r));
        expect(409,"POST",link(p,r,c)+"/confirm","{}");
        expect(204,"POST",link(p,r,c),"{}");assertEquals("NEEDS_REVIEW",state(p,r));
        expect(400,"POST",link(p,r,foreign),"{}");
        for(String suffix:List.of("","/confirm","/remove"))expect(400,"POST",link(q,r,c)+suffix,"{}");
        expect(400,"GET",base(q)+"/requirements/"+r+"/test-cases",null);
        assertEquals("NEEDS_REVIEW",state(p,r));
    }

    @Test void planScopeReadyConflictAndArchiveFollowServiceRules() throws Exception {
        login(admin);long p=project("PLAN");long q=project("FOREIGN");
        var tc=testCase(p);long c=tc.get("id").asLong(),foreign=testCase(q).get("id").asLong();
        var draft=plan(p,List.of());long id=draft.get("id").asLong();int v=draft.get("version").asInt();
        String path=base(p)+"/test-plans/"+id;
        expect(409,"PUT",path,planUpdate(v,"READY"));
        expect(400,"POST",path+"/test-cases/"+foreign,version(v));
        expect(204,"POST",path+"/test-cases/"+c,version(v));
        int scopeVersion=expect(200,"GET",path,null).at("/plan/version").asInt();
        expect(409,"POST",path+"/test-cases/"+c,version(scopeVersion));
        expect(409,"POST",path+"/test-cases/"+c+"/remove",version(v));
        v=expect(200,"GET",path,null).at("/plan/version").asInt();
        expect(409,"PUT",path,planUpdate(v,"READY"));
        expect(200,"PUT",base(p)+"/test-cases/"+c,caseUpdate(tc.get("version").asInt(),"do","READY"));
        var ready=expect(200,"PUT",path,planUpdate(v,"READY"));v=ready.get("version").asInt();
        expect(409,"PUT",path,planUpdate(v-1,"DRAFT"));
        expect(204,"POST",path+"/test-cases/"+c+"/remove",version(v));
        var detail=expect(200,"GET",path,null);
        assertEquals("DRAFT",detail.at("/plan/status").asText());assertEquals(0,detail.get("testCaseIds").size());
        v=detail.at("/plan/version").asInt();
        expect(404,"POST",path+"/test-cases/"+c+"/remove",version(v));
        expect(204,"POST",path+"/archive",version(v));
        expect(409,"PUT",path,planUpdate(v+1,"DRAFT"));
        expect(409,"POST",path+"/test-cases/"+c,version(v+1));
        expect(400,"POST",base(p)+"/test-plans","{\"name\":\"bad\",\"testCaseIds\":["+foreign+"]}");
    }

    @Test void wrongProjectPathsCannotMutateEvenForAdminWithBothProjects() throws Exception {
        login(admin);long p=project("OWNER"),q=project("WRONG");
        var req=requirement(p);long r=req.get("id").asLong();
        var tc=testCase(p);long c=tc.get("id").asLong();
        var plan=plan(p,List.of(c));long id=plan.get("id").asLong();
        expect(400,"GET",base(q)+"/requirements/"+r,null);
        expect(400,"PUT",base(q)+"/requirements/"+r,requirementUpdate(req.get("version").asInt(),"forbidden change"));
        expect(400,"GET",base(q)+"/test-cases/"+c,null);
        expect(400,"PUT",base(q)+"/test-cases/"+c,caseUpdate(tc.get("version").asInt(),"wrong","DRAFT"));
        String wrong=base(q)+"/test-plans/"+id;
        expect(400,"GET",wrong,null);
        expect(400,"PUT",wrong,planUpdate(plan.get("version").asInt(),"DRAFT"));
        for(String suffix:List.of("/archive","/test-cases/"+c,"/test-cases/"+c+"/remove"))
            expect(400,"POST",wrong+suffix,version(plan.get("version").asInt()));
        assertEquals(req.get("version"),expect(200,"GET",base(p)+"/requirements/"+r,null).get("version"));
        assertEquals(tc.get("version"),expect(200,"GET",base(p)+"/test-cases/"+c,null).at("/testCase/version"));
        assertEquals(plan.get("version"),expect(200,"GET",base(p)+"/test-plans/"+id,null).at("/plan/version"));
    }

    @Test void testerDeveloperAndRevokedMembershipAreCheckedForOldSessions() throws Exception {
        login(admin);long p=project("ROLES");
        member(p,tester,ProjectRole.TESTER,MembershipStatus.ACTIVE);
        member(p,developer,ProjectRole.DEVELOPER,MembershipStatus.ACTIVE);
        login(tester);
        long r=requirement(p).get("id").asLong(),c=testCase(p).get("id").asLong();
        expect(204,"POST",link(p,r,c),"{}");
        long plan=plan(p,List.of(c)).get("id").asLong();
        login(developer);
        expect(200,"GET",base(p)+"/test-cases/"+c,null);
        expect(403,"POST",base(p)+"/test-cases","{\"title\":\"case\",\"priority\":\"HIGH\",\"steps\":[]}");
        expect(403,"POST",link(p,r,c)+"/confirm","{}");
        expect(403,"POST",base(p)+"/test-plans/"+plan+"/archive",version(0));
        login(tester);member(p,tester,ProjectRole.TESTER,MembershipStatus.INACTIVE);
        expect(403,"POST",link(p,r,c)+"/confirm","{}");
        expect(403,"GET",base(p)+"/test-cases/"+c,null);
        expect(403,"PUT",base(p)+"/requirements/"+r,requirementUpdate(0,"denied"));
    }

    @Test void archivedProjectRejectsAssetWritesButKeepsReads() throws Exception {
        login(admin);long p=project("ARCHIVE");long r=requirement(p).get("id").asLong(),c=testCase(p).get("id").asLong();
        long plan=plan(p,List.of(c)).get("id").asLong();
        // No Project archive HTTP endpoint in this round; arrange archive through its existing Service.
        var project=projectService.get(admin.id(),p);
        projectService.archive(admin.id(),p,project.lockVersion());
        expect(200,"GET",base(p)+"/test-cases/"+c,null);
        expect(409,"PUT",base(p)+"/requirements/"+r,requirementUpdate(0,"denied"));
        expect(409,"PUT",base(p)+"/test-cases/"+c,caseUpdate(0,"do","READY"));
        expect(409,"POST",link(p,r,c),"{}");
        expect(409,"POST",base(p)+"/test-plans/"+plan+"/archive",version(0));
    }
}
