package io.github.lz007001cn.veriqra.integration;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** No database access: exercises refusal and cleanup paths without risking any real schema. */
class MysqlFixtureSafetyTest {
    @Test void developmentAndAmbiguousUrlsAreRejectedBeforeConnecting() {
        for(String url:List.of("jdbc:mysql://localhost:3306/veriqra",
                "jdbc:mysql://localhost:3306/veriqra_test_demo?databaseTerm=SCHEMA",
                "jdbc:mysql://remote:3306/veriqra_test_demo", "jdbc:mysql://localhost:0/veriqra_test_demo",
                "jdbc:mysql://localhost:65536/veriqra_test_demo", "jdbc:mysql://localhost:3306/veriqra_test_")) {
            assertThrows(IllegalArgumentException.class,()->MysqlFixture.requireTestUrl(url),url);
        }
        assertEquals("veriqra_test_ci",MysqlFixture.requireTestUrl("jdbc:mysql://127.0.0.1:3306/veriqra_test_ci"));
    }

    @Test void nonemptySchemaRefusedAndItsTablesAreNeverDropped() throws Exception {
        Stub db=new Stub(); db.existingTables=1;
        assertThrows(SQLException.class,()->MysqlFixture.requireEmptySchema(db.connection,"veriqra_test_ci"));
        MysqlFixture.cleanup(null,db.connection,"veriqra_test_ci",List.of());
        assertTrue(db.executed.isEmpty()); assertTrue(db.closed);
    }

    @Test void partialSetupOnlyCleansSuccessfullyCreatedTables() throws Exception {
        Stub db=new Stub(); db.failCreate="projects"; List<String> owned=new ArrayList<>();
        assertThrows(SQLException.class,()->MysqlFixture.installSchema(db.connection,
                "CREATE TABLE `users` (id BIGINT); CREATE TABLE `projects` (id BIGINT); CREATE TABLE `untouched` (id BIGINT);",owned));
        assertEquals(List.of("users"),owned);
        MysqlFixture.cleanup(null,db.connection,"veriqra_test_ci",owned);
        assertEquals(List.of("DROP TABLE IF EXISTS `veriqra_test_ci`.`users`"),db.executed.stream().filter(s->s.startsWith("DROP")).toList());
    }

    @Test void changedCatalogRefusesDestructiveCleanup() {
        Stub db=new Stub(); db.catalog="veriqra";
        assertThrows(SQLException.class,()->MysqlFixture.cleanup(null,db.connection,"veriqra_test_ci",List.of("users")));
        assertTrue(db.executed.isEmpty()); assertTrue(db.closed);
    }

    @Test void cleanupContinuesAndCloseFailureDoesNotMaskFirstDropFailure() {
        Stub db=new Stub(); db.failDrop=true; db.failClose=true;
        var error=assertThrows(SQLException.class,()->MysqlFixture.cleanup(null,db.connection,"veriqra_test_ci",List.of("users","projects")));
        assertEquals("drop failure",error.getMessage());
        assertEquals(2,error.getSuppressed().length);
        assertEquals("owner close failure",error.getSuppressed()[1].getMessage()); assertTrue(db.closed);
        assertEquals(2,db.executed.size());
    }

    private static final class Stub {
        String catalog="veriqra_test_ci";
        String failCreate;
        boolean failDrop,failClose,closed;
        int existingTables;
        final List<String> executed=new ArrayList<>();
        final Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            return switch(m.getName()) {
                case "getCatalog" -> catalog;
                case "createStatement", "prepareStatement" -> statement();
                case "close" -> { closed=true; if(failClose) throw new SQLException("owner close failure"); yield null; }
                default -> throw new SQLFeatureNotSupportedException(m.getName());
            };
        });
        private PreparedStatement statement() {
            return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                return switch(m.getName()) {
                    case "execute" -> {
                        String sql=((String)a[0]).strip(); executed.add(sql);
                        if(failCreate!=null && sql.startsWith("CREATE TABLE `"+failCreate+"`")) throw new SQLException("create failure");
                        if(failDrop && sql.startsWith("DROP")) throw new SQLException("drop failure");
                        yield false;
                    }
                    case "close", "setString" -> null;
                    case "executeQuery" -> Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(r,rm,ra)->switch(rm.getName()) {
                        case "next" -> true;
                        case "getInt" -> existingTables;
                        case "close" -> null;
                        default -> throw new SQLFeatureNotSupportedException(rm.getName());
                    });
                    default -> throw new SQLFeatureNotSupportedException(m.getName());
                };
            });
        }
    }
}
