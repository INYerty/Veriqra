package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class UserDaoIntegrationTest extends MysqlFixture {
    @Test void insertAndFindRoundTrip() {
        User saved=tx.inTransaction(c->new JdbcUserDao(c).insert(user("alice")));
        assertNotNull(saved.id()); assertEquals(0,saved.lockVersion()); assertNotNull(saved.createdAt());
        tx.inTransaction(c->{
            var dao=new JdbcUserDao(c);
            assertEquals(saved,dao.findById(saved.id()).orElseThrow());
            assertEquals(saved,dao.findByUsername("alice").orElseThrow());
            assertFalse(c.isClosed()); return null;
        });
    }

    @Test void absentRowsReturnEmptyAndParametersAreNotSql() {
        tx.inTransaction(c->{
            var dao=new JdbcUserDao(c); dao.insert(user("alice"));
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.findByUsername("' OR 1=1 --").isEmpty());
            assertTrue(dao.findByUsername("ALICE").isEmpty()); return null;
        });
    }

    @Test void updateMutableColumnsAndAdvanceVersionPreservingIdentity() {
        tx.inTransaction(c->{
            var dao=new JdbcUserDao(c); var old=dao.insert(user("alice"));
            var changed=new User(old.id(),"ignored-rename","新名称","different-fixture-hash",SystemRole.ADMIN,
                    UserStatus.DISABLED,null,null,old.lockVersion());
            var updated=dao.update(changed);
            assertEquals("alice",updated.username()); assertEquals("新名称",updated.displayName());
            assertEquals(UserStatus.DISABLED,updated.status()); assertEquals(SystemRole.ADMIN,updated.systemRole());
            assertEquals(old.createdAt(),updated.createdAt()); assertEquals(1,updated.lockVersion());
            assertFalse(updated.updatedAt().isBefore(old.updatedAt())); return null;
        });
    }

    @Test void staleUpdateIsRejected() {
        tx.inTransaction(c->{var dao=new JdbcUserDao(c); var old=dao.insert(user("alice")); dao.update(old);
            assertThrows(OptimisticLockException.class,()->dao.update(old)); return null;});
    }

    @Test void missingUpdateIsRejected() {
        assertThrows(OptimisticLockException.class,()->tx.inTransaction(c->new JdbcUserDao(c).update(
                new User(-1L,"absent","name","hash",SystemRole.USER,UserStatus.ACTIVE,null,null,0))));
    }

    @Test void uniqueViolationTranslatedAndTransactionRolledBack() throws Exception {
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->{
            var dao=new JdbcUserDao(c); dao.insert(user("same")); return dao.insert(user("same"));
        }));
        assertEquals("23000",error.getSqlState()); assertEquals(1062,error.getVendorCode());
        assertInstanceOf(SQLException.class,error.getCause()); assertEquals(0,count("users"));
    }

    @Test void checkViolationTranslated() {
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->new JdbcUserDao(c).insert(user(" "))));
        assertEquals(3819,error.getVendorCode());
    }

    @Test void datetimeMicrosecondsRoundTripWithoutZoneConversion() {
        LocalDateTime expected=LocalDateTime.of(2026,9,7,8,30,12,123456000);
        tx.inTransaction(c->{
            var dao=new JdbcUserDao(c); User saved=dao.insert(user("micros"));
            try(var s=c.prepareStatement("UPDATE users SET created_at=?, updated_at=? WHERE id=?")) {
                s.setObject(1,expected); s.setObject(2,expected); s.setLong(3,saved.id()); s.executeUpdate();
            }
            assertEquals(expected,dao.findById(saved.id()).orElseThrow().createdAt()); return null;
        });
    }

    @Test void unsignedVersionDoesNotSilentlyOverflowInteger() {
        tx.inTransaction(c->{
            var dao=new JdbcUserDao(c); User saved=dao.insert(user("large_version"));
            try(var s=c.prepareStatement("UPDATE users SET lock_version=? WHERE id=?")) {
                s.setLong(1,2147483648L); s.setLong(2,saved.id()); s.executeUpdate();
            }
            var e=assertThrows(DataAccessException.class,()->dao.findById(saved.id()));
            assertEquals("22003",e.getSqlState()); return null;
        });
    }

    @Test void maximumJavaVersionCannotBeIncremented() {
        var saved=new User(1L,"alice","name","hash",SystemRole.USER,UserStatus.ACTIVE,null,null,Integer.MAX_VALUE);
        assertThrows(DataAccessException.class,()->tx.inTransaction(c->new JdbcUserDao(c).update(saved)));
    }
}
