package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface DefectService {
    Defect create(Long actorUserId, CreateDefectCommand command);
    Defect get(Long actorUserId, Long defectId);
    List<Defect> listByProject(Long actorUserId, Long projectId);
    Defect update(Long actorUserId, UpdateDefectCommand command);
    Defect transition(Long actorUserId, TransitionDefectCommand command);
    Defect reopen(Long actorUserId, ReopenDefectCommand command);
    TestAttemptDefect addEvidence(Long actorUserId, Long defectId, Long failureAttemptId);
    void removeEvidence(Long actorUserId, Long defectId, Long failureAttemptId);
    List<TestAttemptDefect> listEvidence(Long actorUserId, Long defectId);
    List<TestAttemptDefect> listDefectsForAttempt(Long actorUserId, Long attemptId);
}
