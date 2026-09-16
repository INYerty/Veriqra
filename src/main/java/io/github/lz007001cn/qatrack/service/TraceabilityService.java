package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.TestCaseRequirement;

public interface TraceabilityService {
    TestCaseRequirement attach(Long actorUserId, Long requirementId, Long testCaseId);
    TestCaseRequirement remove(Long actorUserId, Long requirementId, Long testCaseId);
    TestCaseRequirement confirm(Long actorUserId, Long requirementId, Long testCaseId);
    boolean isActiveLink(Long actorUserId, Long requirementId, Long testCaseId);
    boolean isConfirmedLink(Long actorUserId, Long requirementId, Long testCaseId);
}
