package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.TestCase;
import java.util.List;
import java.util.Optional;

/** Caller owns the connection/transaction. No authorization or workflow transitions. */
public interface TestCaseDao {
    Optional<TestCase> findById(Long id);
    /** Requires an outer transaction; locks the parent for coordinated child writes. */
    Optional<TestCase> findByIdForUpdate(Long id);
    Optional<TestCase> findByKey(Long projectId, Long keyNo);
    List<TestCase> listByProject(Long projectId);
    /** Ignores generated id/timestamps/version. Write and readback in an outer transaction. */
    TestCase insert(TestCase value);
    /** Uses id + lockVersion; projectId/keyNo/creator/createdAt remain immutable. */
    TestCase update(TestCase value);
}
