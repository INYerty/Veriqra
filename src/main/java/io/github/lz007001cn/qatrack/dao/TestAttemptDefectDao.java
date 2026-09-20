package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.TestAttemptDefect;
import java.util.*;

/** Evidence row access. Caller checks FAIL, same project, permissions and parent state. */
public interface TestAttemptDefectDao {
    TestAttemptDefect add(TestAttemptDefect value);
    Optional<TestAttemptDefect> find(Long attemptId, Long defectId);
    boolean existsRecord(Long attemptId, Long defectId);
    /** Returns association records, ordered by defect ID. */
    List<TestAttemptDefect> listDefectsByAttempt(Long attemptId);
    /** Returns association records, ordered by attempt ID. */
    List<TestAttemptDefect> listAttemptsByDefect(Long defectId);
    /** Current locking read of the evidence set; caller locks Attempt hierarchy, then Defect first. */
    List<TestAttemptDefect> listAttemptsByDefectForUpdate(Long defectId);
    /** Correct an erroneous association only; never removes either endpoint. */
    boolean remove(Long attemptId, Long defectId);
}
