package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.model.*;
import java.util.HexFormat;
import java.util.UUID;

/** Data builders only; no parsing or import workflow. */
abstract class ImportFixture extends ExecutionFixture {
    protected TestAutomationIdentity identity(Parents p, String key) {
        return new TestAutomationIdentity(null, p.projectId(), AutomationSource.JUNIT, "module ' ? 中文", key, null);
    }
    protected TestAutomationMapping mapping(Long identityId, Long caseId, Long userId) {
        return new TestAutomationMapping(null, identityId, caseId, AutomationMappingStatus.ACTIVE, userId, null, null, null);
    }
    protected byte[] hash() {
        return HexFormat.of().parseHex("008002030405060708090a0b0c0d0e0f101112131415161718191a1b1c1dfeff");
    }
    protected TestImport batch(Execution e, UUID requestKey) {
        return new TestImport(null, e.run().id(), requestKey, hash(), "module ' ? 中文", "report.xml", e.parents().userId(), null);
    }
    protected TestAttempt automated(Execution e, TestImport batch, TestAutomationMapping mapping, int number) {
        return new TestAttempt(null, e.runCase().id(), number, TestAttemptStatus.FAIL, null, batch.id(), mapping.id(),
                null, null, null, "imported evidence", "failure", UUID.randomUUID());
    }
}
