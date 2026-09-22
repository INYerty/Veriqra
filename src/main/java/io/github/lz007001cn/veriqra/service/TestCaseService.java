package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.command.*;
import java.util.List;

public interface TestCaseService {
    TestCase get(Long actorUserId, Long projectId, Long testCaseId);
    TestCase update(Long actorUserId, Long projectId, UpdateTestCaseCommand command);
    TestCaseDetails getDetails(Long actorUserId, Long projectId, Long testCaseId);
    TestCase create(Long actorUserId, CreateTestCaseCommand command);
    TestCase get(Long actorUserId, Long testCaseId);
    List<TestCase> listByProject(Long actorUserId, Long projectId);
    TestCase update(Long actorUserId, UpdateTestCaseCommand command);
    List<TestStep> listSteps(Long actorUserId, Long testCaseId);
}
