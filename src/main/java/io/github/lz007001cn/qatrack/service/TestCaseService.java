package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface TestCaseService {
    TestCase create(Long actorUserId, CreateTestCaseCommand command);
    TestCase get(Long actorUserId, Long testCaseId);
    List<TestCase> listByProject(Long actorUserId, Long projectId);
    TestCase update(Long actorUserId, UpdateTestCaseCommand command);
    List<TestStep> listSteps(Long actorUserId, Long testCaseId);
}
