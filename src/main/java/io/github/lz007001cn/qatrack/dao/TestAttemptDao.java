package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.TestAttempt;
import java.util.*;

/** Append-only result fact. Import/mapping IDs are references only; no import behavior. Caller owns connection/transaction. */
public interface TestAttemptDao {
    Optional<TestAttempt> findById(Long id);
    /** Current locking read after the caller has locked Run then RunCase. */
    Optional<TestAttempt> findByIdForUpdate(Long id);
    List<TestAttempt> listByRunCase(Long testRunCaseId);
    Optional<TestAttempt> findLatestByRunCase(Long testRunCaseId);
    /** Current locking read, even after an earlier repeatable-read snapshot.
     * Caller must first lock Run then RunCase, including when no Attempt exists.
     * Caller supplies the next positive sequence within Integer range; no automatic allocation/retry. */
    Optional<TestAttempt> findLatestByRunCaseForUpdate(Long testRunCaseId);
    Optional<TestAttempt> findBySubmissionKey(UUID submissionKey);
    /** Current locking read for manual submission idempotency; requires an outer transaction. */
    Optional<TestAttempt> findBySubmissionKeyForUpdate(UUID submissionKey);
    /** Ignores generated fields; insert and readback belong in an outer transaction. */
    TestAttempt insert(TestAttempt value);
}
