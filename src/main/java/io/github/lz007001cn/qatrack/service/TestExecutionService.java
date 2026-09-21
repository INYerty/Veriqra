package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.RecordAttemptCommand;
import java.util.*;

public interface TestExecutionService {
    TestAttempt recordAttempt(Long actorUserId, Long projectId, Long testRunId, RecordAttemptCommand command);
    List<TestAttempt> listAttempts(Long actorUserId, Long projectId, Long testRunId, Long runCaseId);
    TestAttempt recordAttempt(Long actorUserId, RecordAttemptCommand command);
    List<TestAttempt> listAttempts(Long actorUserId, Long runCaseId);
    /** Empty means NOT_RUN; NOT_RUN is deliberately not a TestAttemptStatus. */
    Optional<TestAttemptStatus> currentOutcome(Long actorUserId, Long runCaseId);
}
