package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface TestPlanService {
    TestPlan create(Long actorUserId, CreateTestPlanCommand command);
    TestPlan get(Long actorUserId, Long testPlanId);
    List<TestPlanCase> listCases(Long actorUserId, Long testPlanId);
    TestPlan update(Long actorUserId, UpdateTestPlanCommand command);
    TestPlan archive(Long actorUserId, Long testPlanId, Integer lockVersion);
    TestPlanCase addCase(Long actorUserId, Long testPlanId, Long testCaseId, Integer planLockVersion);
    void removeCase(Long actorUserId, Long testPlanId, Long testCaseId, Integer planLockVersion);
}
