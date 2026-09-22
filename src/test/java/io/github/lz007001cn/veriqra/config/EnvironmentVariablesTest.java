package io.github.lz007001cn.veriqra.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnvironmentVariablesTest {
    @Test void readsOnlyVeriqraVariables() {
        for (String suffix : new String[]{"DB_CONFIG", "PUBLIC_ORIGIN", "SESSION_SECURE"}) {
            assertNull(EnvironmentVariables.get(Map.of(), suffix));
            assertEquals("current", EnvironmentVariables.get(Map.of("VERIQRA_" + suffix, "current"), suffix));
        }
    }

    @Test void databaseFieldsUseOnlyVeriqraNames() {
        var config = DatabaseConfig.load(null, Map.of(
                "VERIQRA_DB_PASSWORD", "current",
                "VERIQRA_DB_MAX_POOL_SIZE", "4"));
        assertEquals("current", config.password());
        assertEquals(4, config.maxPoolSize());
    }
}
