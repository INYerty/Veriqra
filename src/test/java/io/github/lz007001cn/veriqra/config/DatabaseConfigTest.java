package io.github.lz007001cn.veriqra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {
    @TempDir Path directory;
    @Test void defaultsAreLoadedWhenPasswordProvidedExternally() {
        var c=DatabaseConfig.load(directory.resolve("missing"),Map.of("VERIQRA_DB_PASSWORD","test-only"));
        assertEquals("jdbc:mysql://localhost:3306/veriqra",c.jdbcUrl());
        assertEquals(2,c.initialPoolSize()); assertEquals(8,c.maxPoolSize()); assertEquals(Duration.ofSeconds(3),c.acquireTimeout());
    }
    @Test void environmentOverridesFileWhichOverridesDefaults() throws Exception {
        Path file=directory.resolve("db.properties");
        Files.writeString(file,"username=from_file\npassword=file-only\nmaxPoolSize=4\ninitialPoolSize=1\n");
        var c=DatabaseConfig.load(file,Map.of("VERIQRA_DB_USERNAME","from_env","VERIQRA_DB_PASSWORD","env-only","VERIQRA_DB_ACQUIRE_TIMEOUT","90"));
        assertEquals("from_env",c.username()); assertEquals("env-only",c.password());
        assertEquals(4,c.maxPoolSize()); assertEquals(1,c.initialPoolSize()); assertEquals(Duration.ofMillis(90),c.acquireTimeout());
    }
    @Test void missingPasswordFailsWithoutEmbeddingSecrets() {
        assertThrows(IllegalArgumentException.class,()->DatabaseConfig.load(null,Map.of()));
    }
    @Test void invalidPoolBoundsRejected() {
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/db","u","",3,2,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/db","u","",0,0,Duration.ofSeconds(1)));
    }
    @Test void invalidTimeoutRejected() {
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/db","u","",0,1,Duration.ZERO));
    }
    @Test void durationAndNumericExtremesFailBeforePoolConstruction() {
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/db","u","",0,1,Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/db","u","",0,1,Duration.ofMillis(300001)));
        assertThrows(IllegalArgumentException.class,()->DatabaseConfig.load(null,Map.of("VERIQRA_DB_PASSWORD","test","VERIQRA_DB_MAX_POOL_SIZE","2147483648")));
    }
    @Test void explicitEmptyEnvironmentValuesDoNotFallBackToFile() throws Exception {
        Path file=directory.resolve("db.properties");
        Files.writeString(file,"username=file-user\npassword=file-password\n");
        assertEquals("",DatabaseConfig.load(file,Map.of("VERIQRA_DB_PASSWORD","")).password());
        assertThrows(IllegalArgumentException.class,()->DatabaseConfig.load(file,Map.of("VERIQRA_DB_USERNAME","")));
        assertThrows(IllegalArgumentException.class,()->DatabaseConfig.load(file,Map.of("VERIQRA_DB_ACQUIRE_TIMEOUT","")));
    }
    @Test void malformedNumberIsReportedWithoutValueEcho() {
        var e=assertThrows(IllegalArgumentException.class,()->DatabaseConfig.load(null,Map.of("VERIQRA_DB_PASSWORD","test","VERIQRA_DB_MAX_POOL_SIZE","secret-not-a-number")));
        assertFalse(e.toString().contains("secret-not-a-number"));
    }
    @Test void configToStringRedactsUrlUserAndPassword() {
        var c=new DatabaseConfig("jdbc:mysql://localhost/private","private-user","private-secret",0,1,Duration.ofSeconds(1));
        assertFalse(c.toString().contains("private"));
    }
}
