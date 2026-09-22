package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.exception.*;
import io.github.lz007001cn.veriqra.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDaoIntegrationTest extends MysqlFixture {
    @Test void insertAndFindWithNullableDescription() {
        tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner")); var dao=new JdbcProjectDao(c);
            var saved=dao.insert(project("DEMO",creator.id()));
            assertNotNull(saved.id()); assertNull(saved.description()); assertEquals(0,saved.lockVersion());
            assertEquals(saved,dao.findById(saved.id()).orElseThrow());
            assertEquals(saved,dao.findByKey("DEMO").orElseThrow()); return null;
        });
    }
    @Test void absentRowsAndSqlLikeKeysDoNotMatch() {
        tx.inTransaction(c->{
            var dao=new JdbcProjectDao(c);
            assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.findByKey("' OR 1=1 --").isEmpty()); return null;
        });
    }
    @Test void updatePreservesKeyCreatorAndCreationTime() {
        tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner")); var dao=new JdbcProjectDao(c);
            var old=dao.insert(project("DEMO",creator.id()));
            var edited=new Project(old.id(),"IGNORED","变更后的项目","描述",ProjectStatus.ARCHIVED,-1L,null,null,old.lockVersion());
            var saved=dao.update(edited);
            assertEquals("DEMO",saved.projectKey()); assertEquals(creator.id(),saved.createdBy());
            assertEquals(old.createdAt(),saved.createdAt()); assertEquals("描述",saved.description());
            assertEquals(ProjectStatus.ARCHIVED,saved.status()); assertEquals(1,saved.lockVersion());
            assertThrows(OptimisticLockException.class,()->dao.update(old)); return null;
        });
    }
    @Test void uniqueProjectKeyIsTranslated() {
        var error=assertThrows(DataAccessException.class,()->tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner")); var dao=new JdbcProjectDao(c);
            dao.insert(project("SAME",creator.id())); return dao.insert(project("SAME",creator.id()));
        }));
        assertEquals(1062,error.getVendorCode()); assertEquals("23000",error.getSqlState());
    }
    @Test void foreignKeyViolationIsTranslated() {
        var e=assertThrows(DataAccessException.class,()->tx.inTransaction(c->new JdbcProjectDao(c).insert(project("DEMO",-1L))));
        assertEquals(1452,e.getVendorCode());
    }
    @Test void checkViolationIsTranslated() {
        var e=assertThrows(DataAccessException.class,()->tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner"));
            return new JdbcProjectDao(c).insert(new Project(null,"DEMO"," ",null,ProjectStatus.ACTIVE,creator.id(),null,null,null));
        }));
        assertEquals(3819,e.getVendorCode());
    }
    @Test void twoDaosCommitUsingOneConnection() throws Exception {
        tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner"));
            new JdbcProjectDao(c).insert(project("COMMIT",creator.id())); return null;
        });
        assertEquals(1,count("users")); assertEquals(1,count("projects"));
    }
    @Test void twoDaosRollBackTogetherOnCallerFailure() throws Exception {
        assertThrows(IllegalStateException.class,()->tx.inTransaction(c->{
            var creator=new JdbcUserDao(c).insert(user("owner"));
            new JdbcProjectDao(c).insert(project("ROLLBACK",creator.id())); throw new IllegalStateException("abort unit of work");
        }));
        assertEquals(0,count("users")); assertEquals(0,count("projects"));
    }
    @Test void databaseFailureRollsBackEarlierDaoWrite() throws Exception {
        assertThrows(DataAccessException.class,()->tx.inTransaction(c->{
            new JdbcUserDao(c).insert(user("owner")); return new JdbcProjectDao(c).insert(project("BAD",-1L));
        }));
        assertEquals(0,count("users")); assertEquals(0,count("projects"));
    }
}
