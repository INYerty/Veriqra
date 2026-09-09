package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.*;
import java.util.*;

/** Association records, including INACTIVE. No hard delete or automatic mapping creation. */
public interface TestAutomationMappingDao {
    TestAutomationMapping add(TestAutomationMapping value);
    Optional<TestAutomationMapping> findById(Long id);
    /** Reverse lookup: frozen UNIQUE permits zero or one Case association per Identity. */
    Optional<TestAutomationMapping> findByIdentity(Long automationIdentityId);
    /** Returns mapping records ordered by identity ID, including INACTIVE. */
    List<TestAutomationMapping> listByTestCase(Long testCaseId);
    /** Row existence including INACTIVE; not an active binding or permission check. */
    boolean existsRecord(Long automationIdentityId);
    /** Current locking read. Outer transaction required; first lock the Identity, even for an absent mapping. */
    Optional<TestAutomationMapping> findByIdentityForUpdate(Long automationIdentityId);
    /** Writes supplied Case/status with optimistic locking; identity/creator/createdAt remain unchanged.
     * Caller must lock/check parents and forbid rebinding after any Attempt reference. */
    TestAutomationMapping update(TestAutomationMapping value);
}
