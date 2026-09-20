package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestPlanCaseDaoIntegrationTest extends AssetFixture {
    @Test void planScopeUsesCaseKeyOrderReverseLookupAndPhysicalRemoval() {
        tx.inTransaction(c -> {
            var p = parents(c); var pd = new JdbcTestPlanDao(c); var td = new JdbcTestCaseDao(c);
            var plan = pd.insert(testPlan(p, 1)); var other = pd.insert(testPlan(p, 2));
            var second = td.insert(testCase(p, 20)); var first = td.insert(testCase(p, 10));
            var dao = new JdbcTestPlanCaseDao(c);
            var b = dao.add(new TestPlanCase(plan.id(), second.id(), p.userId(), null));
            var a = dao.add(new TestPlanCase(plan.id(), first.id(), p.userId(), null));
            var reverse = dao.add(new TestPlanCase(other.id(), first.id(), p.userId(), null));
            assertNotNull(a.addedAt()); assertEquals(List.of(a, b), dao.listByTestPlan(plan.id()));
            assertEquals(List.of(a, reverse), dao.listByTestCase(first.id()));
            assertTrue(dao.exists(plan.id(), first.id())); assertTrue(dao.remove(plan.id(), first.id()));
            assertFalse(dao.exists(plan.id(), first.id())); assertFalse(dao.remove(plan.id(), first.id()));
            assertTrue(td.findById(first.id()).isPresent()); assertTrue(pd.findById(plan.id()).isPresent());
            assertTrue(dao.listByTestPlan(-1L).isEmpty()); return null;
        });
    }

    @Test void duplicateAndForeignKeysFailThroughDao() {
        tx.inTransaction(c -> {
            var p = parents(c); var plan = new JdbcTestPlanDao(c).insert(testPlan(p, 1));
            var t = new JdbcTestCaseDao(c).insert(testCase(p, 1)); var t2 = new JdbcTestCaseDao(c).insert(testCase(p, 2));
            var dao = new JdbcTestPlanCaseDao(c); var link = new TestPlanCase(plan.id(), t.id(), p.userId(), null);
            dao.add(link);
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(link)).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestPlanCase(-1L, t.id(), p.userId(), null))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestPlanCase(plan.id(), -1L, p.userId(), null))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestPlanCase(plan.id(), t2.id(), -1L, null))).getVendorCode());
            return null;
        });
    }

    @Test void crossProjectScopeNeedsServiceValidationAndIsRolledBackHere() {
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var p = parents(c); var plan = new JdbcTestPlanDao(c).insert(testPlan(p, 1));
            var p2 = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            var t = new JdbcTestCaseDao(c).insert(testCase(new Parents(p.userId(), p2.id()), 1));
            var dao = new JdbcTestPlanCaseDao(c);
            dao.add(new TestPlanCase(plan.id(), t.id(), p.userId(), null));
            assertTrue(dao.exists(plan.id(), t.id()));
            throw new IllegalStateException("rollback Service-invariant probe");
        }));
    }

    @Test void lockingScopeReadSeesCommitAfterEarlierRepeatableReadSnapshot() throws Exception {
        record Scope(TestPlan plan, TestCase testCase, Long userId) { }
        Scope scope = tx.inTransaction(c -> {
            var p = parents(c);
            return new Scope(new JdbcTestPlanDao(c).insert(testPlan(p, 1)),
                    new JdbcTestCaseDao(c).insert(testCase(p, 1)), p.userId());
        });
        try (var older = pool.borrow()) {
            older.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            older.setAutoCommit(false);
            var dao = new JdbcTestPlanCaseDao(older);
            assertTrue(dao.listByTestPlan(scope.plan().id()).isEmpty());
            var link = tx.inTransaction(c -> new JdbcTestPlanCaseDao(c).add(new TestPlanCase(
                    scope.plan().id(), scope.testCase().id(), scope.userId(), null)));
            assertTrue(dao.listByTestPlan(scope.plan().id()).isEmpty());
            new JdbcTestPlanDao(older).findByIdForUpdate(scope.plan().id()).orElseThrow();
            assertEquals(List.of(link), dao.listByTestPlanForUpdate(scope.plan().id()));
            older.rollback();
        }
    }
}
