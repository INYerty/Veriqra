package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.exception.DataAccessException;
import io.github.lz007001cn.qatrack.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestImportDaoIntegrationTest extends ImportFixture {
    @Test void uuidHashAndMetadataRoundTripAndDuplicateRequestRejection() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestImportDao(c);
            var key = UUID.fromString("fedcba98-7654-4321-8abc-0123456789ef");
            var saved = dao.insert(batch(e, key)); assertNotNull(saved.importedAt());
            assertEquals(saved, dao.findById(saved.id()).orElseThrow()); assertEquals(saved, dao.findByRequestKey(key).orElseThrow());
            assertArrayEquals(hash(), saved.reportSha256()); assertEquals("module ' ? 中文", saved.sourceNamespace());
            assertEquals("report.xml", saved.originalFilename()); assertEquals(e.parents().userId(), saved.importedBy());
            try (var s = c.prepareStatement("SELECT BIN_TO_UUID(request_key,0), HEX(report_sha256) FROM test_imports WHERE id=?")) {
                s.setLong(1, saved.id()); try (var r = s.executeQuery()) {
                    assertTrue(r.next()); assertEquals(key.toString(), r.getString(1));
                    assertEquals(HexFormat.of().formatHex(hash()).toUpperCase(Locale.ROOT), r.getString(2));
                }
            }
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(batch(e, key))).getVendorCode());
            byte[] otherHash = hash(); otherHash[0] = 42;
            assertEquals(1062, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, e.run().id(), key, otherHash,
                    saved.sourceNamespace(), "different.xml", saved.importedBy(), null))).getVendorCode());
            var second = dao.insert(batch(e, UUID.randomUUID())); // Same payload is allowed with a new request key.
            assertEquals(List.of(saved, second), dao.listByRun(e.run().id()));
            assertEquals(saved, dao.findByRequestKey(key).orElseThrow());
            assertTrue(dao.findByRequestKey(null).isEmpty()); assertTrue(dao.findById(-1L).isEmpty()); assertTrue(dao.listByRun(-1L).isEmpty()); return null;
        });
    }
    @Test void requiredHashAndRequestKeyRejectNullWithoutRows() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestImportDao(c);
            assertEquals(1048, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, e.run().id(), UUID.randomUUID(), null,
                    "module", "file.xml", e.parents().userId(), null))).getVendorCode());
            assertEquals(1048, assertThrows(DataAccessException.class, () -> dao.insert(batch(e, null))).getVendorCode());
            assertTrue(dao.listByRun(e.run().id()).isEmpty()); return null;
        });
    }
    @Test void runAndImporterForeignKeysAndMetadataChecks() {
        tx.inTransaction(c -> {
            var e = execution(c); var dao = new JdbcTestImportDao(c);
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, -1L, UUID.randomUUID(), hash(),
                    "module", "file.xml", e.parents().userId(), null))).getVendorCode());
            assertEquals(1452, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, e.run().id(), UUID.randomUUID(), hash(),
                    "module", "file.xml", -1L, null))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, e.run().id(), UUID.randomUUID(), hash(),
                    " ", "file.xml", e.parents().userId(), null))).getVendorCode());
            assertEquals(3819, assertThrows(DataAccessException.class, () -> dao.insert(new TestImport(null, e.run().id(), UUID.randomUUID(), hash(),
                    "module", " ", e.parents().userId(), null))).getVendorCode()); return null;
        });
    }
}
