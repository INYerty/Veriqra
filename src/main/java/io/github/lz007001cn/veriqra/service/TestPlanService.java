package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.command.*;
import java.util.List;

public interface TestPlanService {
    List<TestPlan> listByProject(Long actorUserId, Long projectId);
    TestPlanDetails getDetails(Long actorUserId, Long projectId, Long testPlanId);
    TestPlan update(Long actorUserId, Long projectId, UpdateTestPlanCommand command);
    TestPlan archive(Long actorUserId, Long projectId, Long testPlanId, Integer lockVersion);
    TestPlanCase addCase(Long actorUserId, Long projectId, Long testPlanId, Long testCaseId, Integer planLockVersion);
    void removeCase(Long actorUserId, Long projectId, Long testPlanId, Long testCaseId, Integer planLockVersion);
    TestPlan create(Long actorUserId, CreateTestPlanCommand command);
    TestPlan get(Long actorUserId, Long testPlanId);
    List<TestPlanCase> listCases(Long actorUserId, Long testPlanId);
    TestPlan update(Long actorUserId, UpdateTestPlanCommand command);
    TestPlan archive(Long actorUserId, Long testPlanId, Integer lockVersion);
    TestPlanCase addCase(Long actorUserId, Long testPlanId, Long testCaseId, Integer planLockVersion);
    void removeCase(Long actorUserId, Long testPlanId, Long testCaseId, Integer planLockVersion);
}
