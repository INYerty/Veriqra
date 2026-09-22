package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.*;
import java.util.*;

public interface AutomationService {
    TestAutomationIdentity registerIdentity(Long actorUserId, Long projectId, AutomationSource source,
                                              String namespace, String externalKey);
    TestAutomationIdentity getIdentity(Long actorUserId, Long identityId);
    List<TestAutomationIdentity> listIdentities(Long actorUserId, Long projectId);
    Optional<TestAutomationMapping> getCurrentMapping(Long actorUserId, Long identityId);
    List<TestAutomationMapping> listMappings(Long actorUserId, Long projectId);
    TestAutomationMapping mapIdentity(Long actorUserId, Long identityId, Long testCaseId,
                                      Integer expectedLockVersion);
    TestAutomationMapping deactivateMapping(Long actorUserId, Long identityId, Integer expectedLockVersion);
}
