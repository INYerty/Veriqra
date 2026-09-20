package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface TestRunService {
    TestRun createFromPlan(Long actorUserId, CreatePlanRunCommand command);
    TestRun createAdHoc(Long actorUserId, CreateAdHocRunCommand command);
    TestRun get(Long actorUserId, Long testRunId);
    List<TestRunCase> listRunCases(Long actorUserId, Long testRunId);
    List<TestRunCaseStep> listSnapshotSteps(Long actorUserId, Long testRunCaseId);
    TestRun complete(Long actorUserId, Long testRunId, Integer lockVersion);
    TestRun cancel(Long actorUserId, Long testRunId, Integer lockVersion);
}
