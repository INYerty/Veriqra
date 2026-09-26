package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.admin.*;
import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.DefaultAuthService;
import io.github.lz007001cn.veriqra.service.auth.PasswordVerifier;
import io.github.lz007001cn.veriqra.service.exception.*;
import io.github.lz007001cn.veriqra.service.support.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Administration mutations run only against the fixture-owned veriqra_test_* schema. */
class AdminServiceIntegrationTest extends MysqlFixture {
    private AdminUserService users;
    private CreditService credits;
    private AdminLogService logs;
    private TelemetryService telemetry;
    private PasswordVerifier passwords;
    private User admin,user;
    private AdminContext context;

    @BeforeEach void setUpAdmin() {
        var transactions=new JdbcServiceTransaction(tx);
        var access=new AdminAccessPolicy();passwords=new PasswordVerifier();
        users=new AdminUserService(transactions,access,passwords);
        credits=new CreditService(transactions,access);
        logs=new AdminLogService(transactions,access);
        telemetry=new TelemetryService(transactions);
        admin=tx.inTransaction(c->new JdbcUserDao(c).insert(new User(null,"admin_r5","Admin",passwords.hash("admin-test-password"),
                SystemRole.ADMIN,UserStatus.ACTIVE,null,null,null)));
        user=tx.inTransaction(c->new JdbcUserDao(c).insert(new User(null,"user_r5","User","fixture-hash",
                SystemRole.USER,UserStatus.ACTIVE,null,null,null)));
        tx.inTransaction(c->{var dao=new JdbcCreditDao(c);dao.createAccount(admin.id());dao.createAccount(user.id());return null;});
        context=new AdminContext(admin.id(),"127.0.0.1",UUID.randomUUID().toString());
    }

    @Test void userCreationIsAuditedHasZeroCreditAndNeverReturnsHash() {
        AdminUserView created=users.create(context,"new_r5","New User","fresh-user-password",SystemRole.USER,UserStatus.ACTIVE);
        assertEquals(0,created.creditBalance());assertEquals("new_r5",created.username());
        assertEquals(0,credits.account(admin.id(),created.id()).balance());
        assertEquals(1,logs.audits(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,"USER_CREATED",null),1,10).total());
        assertThrows(AdminConflictException.class,()->users.create(context,"new_r5","Again","fresh-user-password",SystemRole.USER,UserStatus.ACTIVE));
        assertThrows(ForbiddenException.class,()->users.list(user.id(),1,10));
    }

    @Test void lastActiveAdminCannotBeDisabledOrDemotedAndVersionsAreEnforced() {
        assertEquals("LAST_ADMIN_REQUIRED",assertThrows(AdminConflictException.class,()->users.update(context,admin.id(),
                admin.displayName(),SystemRole.ADMIN,UserStatus.DISABLED,admin.lockVersion())).code());
        assertEquals("LAST_ADMIN_REQUIRED",assertThrows(AdminConflictException.class,()->users.update(context,admin.id(),
                admin.displayName(),SystemRole.USER,UserStatus.ACTIVE,admin.lockVersion())).code());
        var second=users.create(context,"second_r5","Second","second-admin-pass",SystemRole.ADMIN,UserStatus.ACTIVE);
        var changed=users.update(context,admin.id(),"Admin",SystemRole.ADMIN,UserStatus.DISABLED,admin.lockVersion());
        assertEquals(UserStatus.DISABLED,changed.status());
        assertThrows(ForbiddenException.class,()->users.list(admin.id(),1,10));
        assertThrows(ConflictException.class,()->users.update(new AdminContext(second.id(),"127.0.0.1",UUID.randomUUID().toString()),
                user.id(),"Changed",SystemRole.USER,UserStatus.ACTIVE,99));
    }

    @Test void competingAdminDisablesNeverRemoveEveryActiveAdmin() throws Exception {
        var second=users.create(context,"second_r5","Second","second-admin-pass",SystemRole.ADMIN,UserStatus.ACTIVE);
        var secondContext=new AdminContext(second.id(),"127.0.0.1",UUID.randomUUID().toString());
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->{start.await();return users.update(context,admin.id(),"Admin",SystemRole.ADMIN,
                    UserStatus.DISABLED,admin.lockVersion());});
            var other=workers.submit(()->{start.await();return users.update(secondContext,second.id(),"Second",SystemRole.ADMIN,
                    UserStatus.DISABLED,second.lockVersion());});
            start.countDown();
            for(var job:List.of(first,other)) {
                try { job.get(15,TimeUnit.SECONDS); }
                catch(ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof AdminConflictException
                                    || failure.getCause() instanceof io.github.lz007001cn.veriqra.exception.DataAccessException,
                            ()->"Unexpected competing update failure: "+failure.getCause());
                }
            }
        }
        long active=tx.inTransaction(c->{try(var statement=c.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE system_role='ADMIN' AND status='ACTIVE'");
            var rows=statement.executeQuery()){rows.next();return rows.getLong(1);} });
        assertTrue(active>=1,"Concurrent demotions must preserve an active administrator");
    }

    @Test void grantReclaimLedgerAuditAndBatchRollbackAreAtomic() {
        assertEquals(100,credits.grant(context,user.id(),100,"award").balance());
        assertEquals(60,credits.reclaim(context,user.id(),40,"correction").balance());
        var history=credits.history(admin.id(),user.id(),null,null,null,null,null,null,1,10);
        assertEquals(List.of("RECLAIM","GRANT"),history.items().stream().map(CreditEntry::type).toList());
        assertEquals(2,logs.audits(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,null,null),1,10).total());
        assertEquals("INSUFFICIENT_CREDIT_BALANCE",assertThrows(AdminConflictException.class,
                ()->credits.reclaim(context,user.id(),61,"too much")).code());
        assertThrows(ValidationException.class,()->credits.grant(context,user.id(),0,null));
        assertThrows(NotFoundException.class,()->credits.batchGrant(context,List.of(user.id(),999999L),null,false,10,"rollback"));
        assertEquals(60,credits.account(admin.id(),user.id()).balance());
        assertEquals(2,credits.history(admin.id(),user.id(),null,null,null,null,null,null,1,10).total());
        String batch=credits.batchGrant(context,List.of(user.id(),admin.id()),null,false,7,"group award");
        assertEquals(67,credits.account(admin.id(),user.id()).balance());
        assertEquals(2,credits.history(admin.id(),null,null,null,null,batch,null,null,1,10).total());
        assertEquals(1,logs.audits(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,"CREDIT_BATCH_GRANTED",null),1,10).total());
        assertThrows(ForbiddenException.class,()->credits.grant(new AdminContext(user.id(),"127.0.0.1",UUID.randomUUID().toString()),user.id(),1,null));
    }

    @Test void requiredAuditFailureRollsBackCreditAndUserCreation() {
        var invalidAuditContext=new AdminContext(admin.id(),"x".repeat(46),UUID.randomUUID().toString());
        assertThrows(io.github.lz007001cn.veriqra.exception.DataAccessException.class,
                ()->credits.grant(invalidAuditContext,user.id(),9,"audit rollback"));
        assertEquals(0,credits.account(admin.id(),user.id()).balance());
        assertEquals(0,credits.history(admin.id(),user.id(),null,null,null,null,null,null,1,10).total());
        assertThrows(io.github.lz007001cn.veriqra.exception.DataAccessException.class,
                ()->users.create(invalidAuditContext,"audit_rollback","Rollback","temporary-password",SystemRole.USER,UserStatus.ACTIVE));
        assertTrue(tx.inTransaction(c->new JdbcUserDao(c).findByUsername("audit_rollback")).isEmpty());
        assertEquals(0,logs.audits(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,null,null),1,10).total());
    }

    @Test void concurrentReclaimsCannotProduceNegativeBalance() throws Exception {
        credits.grant(context,user.id(),100,null);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var futures=workers.invokeAll(List.of(
                    ()->credits.reclaim(context,user.id(),80,"first"),
                    ()->credits.reclaim(context,user.id(),80,"second")));
            int success=0,conflicts=0;
            for(var future:futures){try{future.get(15,TimeUnit.SECONDS);success++;}
                catch(ExecutionException e){if(e.getCause() instanceof AdminConflictException)conflicts++;else throw e;}}
            assertEquals(1,success);assertEquals(1,conflicts);
        }
        assertEquals(20,credits.account(admin.id(),user.id()).balance());
    }

    @Test void concurrentGrantsDoNotLoseUpdates() throws Exception {
        try(var workers=Executors.newFixedThreadPool(2)) {
            var jobs=workers.invokeAll(List.of(
                    ()->credits.grant(context,user.id(),37,"first"),
                    ()->credits.grant(context,user.id(),63,"second")));
            for(var job:jobs) job.get(15,TimeUnit.SECONDS);
        }
        assertEquals(100,credits.account(admin.id(),user.id()).balance());
        assertEquals(2,credits.history(admin.id(),user.id(),null,null,null,null,null,null,1,10).total());
    }

    @Test void allActiveBatchRejectsChangedPreviewWithoutIssuingCredits() {
        List<Long> preview=credits.activeRecipientIds(admin.id());
        assertEquals(List.of(admin.id(),user.id()),preview);
        users.update(context,user.id(),"User",SystemRole.USER,UserStatus.DISABLED,user.lockVersion());
        assertEquals("BATCH_RECIPIENTS_CHANGED",assertThrows(AdminConflictException.class,
                ()->credits.batchGrant(context,null,preview,true,25,"preview changed")).code());
        assertEquals(0,credits.account(admin.id(),admin.id()).balance());
        assertEquals(0,credits.account(admin.id(),user.id()).balance());
        List<Long> refreshed=credits.activeRecipientIds(admin.id());
        assertEquals(List.of(admin.id()),refreshed);
        credits.batchGrant(context,null,refreshed,true,25,"reviewed");
        assertEquals(25,credits.account(admin.id(),admin.id()).balance());
        assertEquals(0,credits.account(admin.id(),user.id()).balance());
    }

    @Test void allActiveBatchComparesPreviewAsASet() {
        List<Long> preview=new ArrayList<>(credits.activeRecipientIds(admin.id()));
        Collections.reverse(preview);
        String batch=credits.batchGrant(context,null,preview,true,3,"reordered preview");
        assertEquals(3,credits.account(admin.id(),admin.id()).balance());
        assertEquals(3,credits.account(admin.id(),user.id()).balance());
        assertEquals(2,credits.history(admin.id(),null,null,null,null,batch,null,null,1,10).total());
    }

    @Test void passwordResetUsesCurrentHasherAndDisabledUserCannotAuthenticate() {
        var auth=new DefaultAuthService(new JdbcServiceTransaction(tx),new JdbcServiceDaoFactory(),passwords);
        var created=users.create(context,"login_r5","Login","old-login-password",SystemRole.USER,UserStatus.ACTIVE);
        assertEquals(created.id(),auth.authenticate("login_r5","old-login-password").id());
        users.resetPassword(context,created.id(),"new-login-password");
        assertThrows(AuthenticationException.class,()->auth.authenticate("login_r5","old-login-password"));
        assertEquals(created.id(),auth.authenticate("login_r5","new-login-password").id());
        var latest=users.get(admin.id(),created.id());
        users.update(context,created.id(),latest.displayName(),SystemRole.USER,UserStatus.DISABLED,latest.lockVersion());
        assertThrows(AuthenticationException.class,()->auth.current(created.id()));
        assertThrows(AuthenticationException.class,()->auth.authenticate("login_r5","new-login-password"));
    }

    @Test void loginAndAccessTelemetryPreserveOnlyAllowedFields() {
        telemetry.login(null,"missing_r5","127.0.0.1","Mozilla/5.0 Windows Chrome/120","FAILURE","INVALID_CREDENTIALS");
        telemetry.login(user.id(),"user_r5","127.0.0.1","Mozilla/5.0 Linux Firefox/100","SUCCESS",null);
        telemetry.login(null,"user_r5","127.0.0.1","Mozilla/5.0","RATE_LIMITED","RATE_LIMITED");
        telemetry.access(user.id(),"127.0.0.1","Mozilla/5.0 Windows Chrome/120","POST","/veriqra/api/admin/users",403,
                UUID.randomUUID().toString(),12);
        assertEquals(3,logs.logins(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,null,null),1,10).total());
        assertEquals(1,logs.access(admin.id(),new LogFilter(null,null,null,null,null,null,403,null,null,null),1,10).total());
        assertNull(logs.logins(admin.id(),new LogFilter(null,null,"missing_r5",null,null,null,null,null,null,null),1,10).items().getFirst().userId());
        assertFalse(logs.access(admin.id(),new LogFilter(null,null,null,null,null,null,null,null,null,null),1,10).items().getFirst().requestPath().contains("?"));
        telemetry.access(user.id(),"127.0.0.1",null,"GET","/api/users;jsessionid=private-token/projects",404,
                UUID.randomUUID().toString(),1);
        assertEquals("/api/users/projects",logs.access(admin.id(),new LogFilter(null,null,null,null,null,null,404,null,null,null),1,10)
                .items().getFirst().requestPath());
        assertEquals(1,logs.metrics(admin.id()).rateLimitedToday());
    }

    @Test void mysqlRejectsNegativeBalanceZeroLedgerAmountAndUnknownAccount() throws Exception {
        try(var connection=pool.borrow();var statement=connection.createStatement()) {
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate(
                    "UPDATE credit_accounts SET balance=-1 WHERE user_id="+user.id()));
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate(
                    "INSERT INTO credit_transactions(user_id,amount,type,actor_user_id) VALUES("+user.id()+",0,'GRANT',"+admin.id()+")"));
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate(
                    "INSERT INTO credit_transactions(user_id,amount,type,actor_user_id) VALUES(999999,1,'GRANT',"+admin.id()+")"));
        }
        assertEquals(0,credits.account(admin.id(),user.id()).balance());
        assertEquals(0,credits.history(admin.id(),user.id(),null,null,null,null,null,null,1,10).total());
    }

    @Test void aggregateCreditTotalsStayExactAboveSignedLongRange() {
        credits.grant(context,user.id(),Long.MAX_VALUE,"large test grant");
        credits.grant(context,admin.id(),Long.MAX_VALUE,"large test grant");
        var expected=java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO);
        assertEquals(expected,credits.summary(admin.id()).totalBalance());
        assertEquals(expected,credits.summary(admin.id()).totalIssued());
        assertEquals(expected,logs.metrics(admin.id()).creditsIssuedToday());
    }

    @Test void dashboardTodayUsesShanghaiBoundsForLoginAccessAndCredit() {
        var boundaryClock=Clock.fixed(Instant.parse("2026-09-25T17:00:00Z"),ZoneOffset.UTC);
        var boundaryLogs=new AdminLogService(new JdbcServiceTransaction(tx),new AdminAccessPolicy(),boundaryClock);
        tx.inTransaction(c->{
            try(var login=c.prepareStatement("INSERT INTO login_events(user_id,username_attempted,ip_address,browser,operating_system,device_type,result,created_at) VALUES(?,?,'127.0.0.1','Other','Other','Desktop',?,?)");
                var access=c.prepareStatement("INSERT INTO access_logs(user_id,ip_address,browser,operating_system,device_type,http_method,request_path,status_code,request_id,duration_ms,created_at) VALUES(?,'127.0.0.1','Other','Other','Desktop','GET','/test',?,?,1,?)");
                var credit=c.prepareStatement("INSERT INTO credit_transactions(user_id,amount,type,actor_user_id,created_at) VALUES(?,1,?,?,?)")) {
                for(var item:List.of(
                        new Object[]{"2026-09-25T15:59:59","FAILURE"},
                        new Object[]{"2026-09-25T16:00:00","SUCCESS"},
                        new Object[]{"2026-09-26T15:59:59","FAILURE"},
                        new Object[]{"2026-09-26T16:00:00","RATE_LIMITED"})) {
                    login.setLong(1,user.id());login.setString(2,user.username());login.setString(3,(String)item[1]);
                    login.setObject(4,LocalDateTime.parse((String)item[0]));login.executeUpdate();
                }
                for(var item:List.of(
                        new Object[]{"2026-09-25T15:59:59",500},
                        new Object[]{"2026-09-25T16:00:00",200},
                        new Object[]{"2026-09-26T15:59:59",400},
                        new Object[]{"2026-09-26T16:00:00",500})) {
                    access.setLong(1,user.id());access.setInt(2,(Integer)item[1]);
                    access.setString(3,UUID.randomUUID().toString());
                    access.setObject(4,LocalDateTime.parse((String)item[0]));access.executeUpdate();
                }
                for(var item:List.of(
                        new Object[]{"2026-09-25T15:59:59","GRANT"},
                        new Object[]{"2026-09-25T16:00:00","GRANT"},
                        new Object[]{"2026-09-26T15:59:59","RECLAIM"},
                        new Object[]{"2026-09-26T16:00:00","GRANT"})) {
                    credit.setLong(1,user.id());credit.setString(2,(String)item[1]);credit.setLong(3,admin.id());
                    credit.setObject(4,LocalDateTime.parse((String)item[0]));credit.executeUpdate();
                }
            } catch(java.sql.SQLException e) { throw new AssertionError(e); }
            return null;
        });
        var metrics=boundaryLogs.metrics(admin.id());
        assertEquals(1,metrics.loginsToday());
        assertEquals(1,metrics.failedLoginsToday());
        assertEquals(0,metrics.rateLimitedToday());
        assertEquals(2,metrics.requestsToday());
        assertEquals(1,metrics.clientErrorsToday());
        assertEquals(0,metrics.serverErrorsToday());
        assertEquals(1,metrics.uniqueIpsToday());
        assertEquals(java.math.BigInteger.ONE,metrics.creditsIssuedToday());
        assertEquals(java.math.BigInteger.ONE,metrics.creditsReclaimedToday());
    }
}
