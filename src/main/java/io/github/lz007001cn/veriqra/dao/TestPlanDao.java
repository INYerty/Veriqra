package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.TestPlan;
import java.util.List;
import java.util.Optional;

/** Caller owns the connection/transaction. No authorization or workflow transitions. */
public interface TestPlanDao {
    Optional<TestPlan> findById(Long id);
    /** Requires an outer transaction; locks the parent for coordinated child writes. */
    Optional<TestPlan> findByIdForUpdate(Long id);
    Optional<TestPlan> findByKey(Long projectId, Long keyNo);
    List<TestPlan> listByProject(Long projectId);
    /** Ignores generated id/timestamps/version. Write and readback in an outer transaction. */
    TestPlan insert(TestPlan value);
    /** Uses id + lockVersion; projectId/keyNo/creator/createdAt remain immutable. */
    TestPlan update(TestPlan value);
}
