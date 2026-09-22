package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TestCaseRequirementDaoIntegrationTest extends AssetFixture {
    @Test void reviewRemovalAndRelinkPreserveOriginalMetadataAndMicroseconds() {
        tx.inTransaction(c -> {
            var p = parents(c);
            var r = new JdbcRequirementDao(c).insert(requirement(p, 1));
            var t = new JdbcTestCaseDao(c).insert(testCase(p, 1));
            var dao = new JdbcTestCaseRequirementDao(c);
            assertFalse(dao.existsRecord(r.id(), t.id()));
            var link = dao.add(new TestCaseRequirement(r.id(), t.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null));
            assertTrue(dao.existsRecord(r.id(), t.id()), "NEEDS_REVIEW is an existing, unconfirmed record");
            assertNull(link.reviewedBy()); assertNull(link.reviewedAt()); assertNotNull(link.linkedAt());
            var when = LocalDateTime.of(2026, 9, 7, 10, 20, 30, 123456000);
            assertTrue(dao.updateReviewState(r.id(), t.id(), TraceabilityStatus.CONFIRMED, p.userId(), when));
            var reviewed = dao.find(r.id(), t.id()).orElseThrow();
            assertTrue(dao.existsRecord(r.id(), t.id()));
            assertEquals(when, reviewed.reviewedAt()); assertEquals(p.userId(), reviewed.reviewedBy());
            assertTrue(dao.markRemoved(r.id(), t.id()));
            var removed = dao.find(r.id(), t.id()).orElseThrow();
            assertEquals(TraceabilityStatus.REMOVED, removed.status());
            assertNull(removed.reviewedAt()); assertNull(removed.reviewedBy());
            assertTrue(dao.existsRecord(r.id(), t.id()), "REMOVED preserves the database record");
            assertTrue(dao.updateReviewState(r.id(), t.id(), TraceabilityStatus.NEEDS_REVIEW, null, null));
            assertEquals(link.linkedAt(), dao.find(r.id(), t.id()).orElseThrow().linkedAt());
            assertEquals(link.linkedBy(), dao.find(r.id(), t.id()).orElseThrow().linkedBy());
            assertFalse(dao.markRemoved(-1L, t.id())); return null;
        });
    }

    @Test void bothDirectionsIncludeRemovedAssociations() {
        tx.inTransaction(c -> {
            var p = parents(c); var rd = new JdbcRequirementDao(c); var td = new JdbcTestCaseDao(c);
            var r1 = rd.insert(requirement(p, 1)); var r2 = rd.insert(requirement(p, 2));
            var t1 = td.insert(testCase(p, 1)); var t2 = td.insert(testCase(p, 2));
            var dao = new JdbcTestCaseRequirementDao(c);
            dao.add(new TestCaseRequirement(r2.id(), t1.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null));
            dao.add(new TestCaseRequirement(r1.id(), t1.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null));
            dao.add(new TestCaseRequirement(r1.id(), t2.id(), TraceabilityStatus.REMOVED, p.userId(), null, null, null, null));
            assertEquals(java.util.List.of(r1.id(), r2.id()), dao.listByTestCase(t1.id()).stream().map(TestCaseRequirement::requirementId).toList());
            assertEquals(java.util.List.of(t1.id(), t2.id()), dao.listByRequirement(r1.id()).stream().map(TestCaseRequirement::testCaseId).toList());
            assertTrue(dao.listByRequirement(-1L).isEmpty()); assertFalse(dao.existsRecord(-1L, t1.id())); return null;
        });
    }

    @Test void duplicateForeignKeysAndReviewShapeFailThroughDao() {
        tx.inTransaction(c -> {
            var p = parents(c); var r = new JdbcRequirementDao(c).insert(requirement(p, 1));
            var t = new JdbcTestCaseDao(c).insert(testCase(p, 1)); var dao = new JdbcTestCaseRequirementDao(c);
            var link = new TestCaseRequirement(r.id(), t.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null);
            dao.add(link);
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(link)).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.updateReviewState(r.id(), t.id(), TraceabilityStatus.CONFIRMED, null, null)).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.updateReviewState(r.id(), t.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), LocalDateTime.now())).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.updateReviewState(r.id(), t.id(), TraceabilityStatus.CONFIRMED, -1L, LocalDateTime.now())).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestCaseRequirement(-1L, t.id(), TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new TestCaseRequirement(r.id(), -1L, TraceabilityStatus.NEEDS_REVIEW, p.userId(), null, null, null, null))).getVendorCode());
            assertEquals(TraceabilityStatus.NEEDS_REVIEW, dao.find(r.id(), t.id()).orElseThrow().status()); return null;
        });
    }

    @Test void crossProjectAndContentChangeAreExplicitServiceInvariants() {
        // Deliberately roll back the DB-accepted invalid business association; no trigger is implied.
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var p = parents(c); var rd = new JdbcRequirementDao(c); var td = new JdbcTestCaseDao(c);
            var r = rd.insert(requirement(p, 1));
            var p2 = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            var t = td.insert(testCase(new Parents(p.userId(), p2.id()), 1));
            var dao = new JdbcTestCaseRequirementDao(c);
            dao.add(new TestCaseRequirement(r.id(), t.id(), TraceabilityStatus.CONFIRMED, p.userId(), null, p.userId(), LocalDateTime.now(), null));
            rd.update(new Requirement(r.id(), r.projectId(), r.keyNo(), "changed", null, r.priority(), r.status(), r.createdBy(), null, null, r.lockVersion()));
            assertEquals(TraceabilityStatus.CONFIRMED, dao.find(r.id(), t.id()).orElseThrow().status());
            throw new IllegalStateException("rollback Service-invariant probe");
        }));
    }
}
