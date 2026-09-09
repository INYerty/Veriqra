package io.github.lz007001cn.qatrack.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TestImportTest {
    @Test void malformedHashLengthIsRejectedAtConstruction() {
        for (int size : new int[]{0, 31, 33}) {
            assertThrows(IllegalArgumentException.class, () -> new TestImport(null, 1L,
                    UUID.randomUUID(), new byte[size], "module", "report.xml", 2L, null));
        }
    }
    @Test void hashIsDefensivelyCopiedOnConstructionAndAccess() {
        byte[] bytes = new byte[32]; bytes[0] = 12;
        var batch = new TestImport(null, 1L, UUID.randomUUID(), bytes, "module", "report.xml", 2L, null);
        bytes[0] = 99; assertEquals(12, batch.reportSha256()[0]);
        byte[] returned = batch.reportSha256(); returned[0] = 33;
        assertEquals(12, batch.reportSha256()[0]);
    }
    @Test void recordEqualityAndHashCodeUseBinaryContent() {
        var key = UUID.randomUUID();
        var first = new TestImport(1L, 2L, key, new byte[32], "module", "report.xml", 3L, null);
        var second = new TestImport(1L, 2L, key, new byte[32], "module", "report.xml", 3L, null);
        assertEquals(first, second); assertEquals(first.hashCode(), second.hashCode());
        byte[] different = new byte[32]; different[31] = 1;
        assertNotEquals(first, new TestImport(1L, 2L, key, different, "module", "report.xml", 3L, null));
        assertNotEquals(first, null);
        var incomplete = new TestImport(null, null, null, null, null, null, null, null);
        assertNull(incomplete.reportSha256()); assertEquals(incomplete, new TestImport(null, null, null, null, null, null, null, null));
    }
}
