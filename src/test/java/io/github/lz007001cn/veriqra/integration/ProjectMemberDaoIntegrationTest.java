package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectMemberDaoIntegrationTest extends AssetFixture {
    @Test void addReverseLookupLeaveAndRejoinRetainIdentity() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcProjectMemberDao(c);
            assertFalse(dao.existsRecord(p.projectId(), p.userId()));
            var saved = dao.add(new ProjectMember(p.projectId(), p.userId(), ProjectRole.TESTER, MembershipStatus.ACTIVE, null, null, null));
            assertTrue(dao.existsRecord(p.projectId(), p.userId()));
            assertEquals(List.of(saved), dao.listByProject(p.projectId()));
            assertEquals(List.of(saved), dao.listByUser(p.userId(), MembershipStatus.ACTIVE));
            var inactive = dao.update(new ProjectMember(saved.projectId(), saved.userId(), ProjectRole.DEVELOPER,
                    MembershipStatus.INACTIVE, null, null, saved.lockVersion()));
            assertEquals(saved.joinedAt(), inactive.joinedAt()); assertEquals(1, inactive.lockVersion());
            assertTrue(dao.existsRecord(p.projectId(), p.userId()), "INACTIVE preserves the database record");
            assertEquals(MembershipStatus.INACTIVE, dao.find(p.projectId(), p.userId()).orElseThrow().status());
            assertTrue(dao.listByUser(p.userId(), MembershipStatus.ACTIVE).isEmpty());
            assertEquals(List.of(inactive), dao.listByUser(p.userId(), MembershipStatus.INACTIVE));
            assertThrows(OptimisticLockException.class, () -> dao.update(saved));
            var active = dao.update(new ProjectMember(saved.projectId(), saved.userId(), ProjectRole.TESTER,
                    MembershipStatus.ACTIVE, null, null, inactive.lockVersion()));
            assertEquals(saved.joinedAt(), active.joinedAt()); assertEquals(2, active.lockVersion());
            assertFalse(dao.existsRecord(-1L, p.userId()));
            assertTrue(dao.find(p.projectId(), null).isEmpty());
            return null;
        });
    }

    @Test void compositeKeyAndBothForeignKeysRejectInvalidMemberships() {
        tx.inTransaction(c -> {
            var p = parents(c); var dao = new JdbcProjectMemberDao(c);
            var m = new ProjectMember(p.projectId(), p.userId(), ProjectRole.TESTER, MembershipStatus.ACTIVE, null, null, null);
            dao.add(m);
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.add(m)).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new ProjectMember(-1L, p.userId(),
                    ProjectRole.TESTER, MembershipStatus.ACTIVE, null, null, null))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.add(new ProjectMember(p.projectId(), -1L,
                    ProjectRole.TESTER, MembershipStatus.ACTIVE, null, null, null))).getVendorCode());
            var other = new JdbcProjectDao(c).insert(project("OTHER", p.userId()));
            dao.add(new ProjectMember(other.id(), p.userId(), ProjectRole.DEVELOPER, MembershipStatus.ACTIVE, null, null, null));
            assertEquals(2, dao.listByUser(p.userId(), MembershipStatus.ACTIVE).size());
            return null;
        });
    }

    @Test void membershipRollsBackWithParentWrites() throws Exception {
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            var p = parents(c);
            new JdbcProjectMemberDao(c).add(new ProjectMember(p.projectId(), p.userId(), ProjectRole.TESTER,
                    MembershipStatus.ACTIVE, null, null, null));
            throw new IllegalStateException("caller failure");
        }));
        assertEquals(0, count("projects")); assertEquals(0, count("users"));
    }
}
