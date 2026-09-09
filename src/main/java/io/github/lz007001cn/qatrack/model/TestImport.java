package io.github.lz007001cn.qatrack.model;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable batch evidence. Project is obtained through Run; this row has no source enum. */
public record TestImport(Long id, Long testRunId, UUID requestKey, byte[] reportSha256,
                         String sourceNamespace, String originalFilename, Long importedBy,
                         LocalDateTime importedAt) {
    public TestImport {
        if (reportSha256 != null && reportSha256.length != 32) {
            throw new IllegalArgumentException("reportSha256 must contain exactly 32 bytes");
        }
        reportSha256 = reportSha256 == null ? null : reportSha256.clone();
    }
    @Override public byte[] reportSha256() { return reportSha256 == null ? null : reportSha256.clone(); }

    // Arrays need value equality as well as defensive copies to preserve record semantics.
    @Override public boolean equals(Object other) {
        return this == other || other instanceof TestImport value
                && Objects.equals(id, value.id) && Objects.equals(testRunId, value.testRunId)
                && Objects.equals(requestKey, value.requestKey) && Arrays.equals(reportSha256, value.reportSha256)
                && Objects.equals(sourceNamespace, value.sourceNamespace)
                && Objects.equals(originalFilename, value.originalFilename)
                && Objects.equals(importedBy, value.importedBy) && Objects.equals(importedAt, value.importedAt);
    }
    @Override public int hashCode() {
        return Objects.hash(id, testRunId, requestKey, Arrays.hashCode(reportSha256), sourceNamespace,
                originalFilename, importedBy, importedAt);
    }
}
