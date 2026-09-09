package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.*;
import java.util.*;

/** Insert-only stable identities. Caller owns connection and transaction. */
public interface TestAutomationIdentityDao {
    TestAutomationIdentity insert(TestAutomationIdentity value);
    Optional<TestAutomationIdentity> findById(Long id);
    Optional<TestAutomationIdentity> findByExternalKey(Long projectId, AutomationSource source, String namespace, String externalKey);
    List<TestAutomationIdentity> listByProject(Long projectId);
    /** Outer transaction required; lock Identity before Mapping, Run and RunCase. */
    Optional<TestAutomationIdentity> findByIdForUpdate(Long id);
}
