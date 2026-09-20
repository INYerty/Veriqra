package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.exception.*;
import io.github.lz007001cn.qatrack.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefectDaoIntegrationTest extends ExecutionFixture {
    @Test void insertFindUpdatePreservesIdentityAndUsesOptimisticLock() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcDefectDao(c); var original = dao.insert(defect(p, 2));
            var first = dao.insert(defect(p, 1));
            assertEquals(List.of(first, original), dao.listByProject(p.projectId()));
            assertEquals(original, dao.findById(original.id()).orElseThrow());
            assertEquals(original, dao.findByIdForUpdate(original.id()).orElseThrow());
            assertEquals(original, dao.findByKey(p.projectId(), 2L).orElseThrow());
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.findByKey(-1L, 2L).isEmpty());
            assertNull(original.assigneeId()); assertEquals(0, original.lockVersion());
            var input = new Defect(original.id(), -1L, -1L, "fixed", "detail", DefectSeverity.CRITICAL, Priority.LOW,
                    DefectStatus.CLOSED, -1L, p.userId(), "resolution ' ? 中文", null, null, original.lockVersion());
            var updated = dao.update(input); // DAO does not enforce OPEN -> CLOSED workflow permissions.
            assertEquals(original.projectId(), updated.projectId()); assertEquals(original.keyNo(), updated.keyNo());
            assertEquals(original.reporterId(), updated.reporterId()); assertEquals(original.createdAt(), updated.createdAt());
            assertEquals(1, updated.lockVersion()); assertEquals(DefectStatus.CLOSED, updated.status());
            assertEquals(DefectSeverity.CRITICAL, updated.severity()); assertEquals(Priority.LOW, updated.priority());
            assertEquals(p.userId(), updated.assigneeId()); assertEquals(input.resolutionNote(), updated.resolutionNote());
            assertThrows(OptimisticLockException.class, () -> dao.update(input)); return null;
        });
    }

    @Test void projectKeyUniquenessAndForeignKeys() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcDefectDao(c); var saved = dao.insert(defect(p, 1));
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(defect(p, 1))).getVendorCode());
            var otherProject = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            var other = dao.insert(defect(new Parents(p.userId(), otherProject.id()), 1));
            assertEquals(List.of(other), dao.listByProject(otherProject.id()));
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(defect(new Parents(p.userId(), -1L), 1))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(defect(new Parents(-1L, p.projectId()), 2))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.update(new Defect(saved.id(), saved.projectId(), saved.keyNo(),
                    saved.title(), null, saved.severity(), saved.priority(), saved.status(), saved.reporterId(), -1L, null, null, null, 0))).getVendorCode()); return null;
        });
    }

    @Test void checkConstraintsAndAllFrozenStatuses() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcDefectDao(c); var saved = dao.insert(defect(p, 1));
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(defect(p, 0))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.update(new Defect(saved.id(), saved.projectId(), saved.keyNo(),
                    " ", null, saved.severity(), saved.priority(), saved.status(), saved.reporterId(), null, null, null, null, 0))).getVendorCode());
            for (var status : List.of(DefectStatus.RESOLVED, DefectStatus.CLOSED)) {
                assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.update(new Defect(saved.id(), saved.projectId(), saved.keyNo(),
                        saved.title(), null, saved.severity(), saved.priority(), status, saved.reporterId(), null, " ", null, null, 0))).getVendorCode());
            }
            var current = saved;
            for (var status : DefectStatus.values()) {
                current = dao.update(new Defect(current.id(), current.projectId(), current.keyNo(), current.title(), null,
                        current.severity(), current.priority(), status, current.reporterId(), null, "reason", null, null, current.lockVersion()));
                assertEquals(status, current.status());
            }
            return null;
        });
    }
}
