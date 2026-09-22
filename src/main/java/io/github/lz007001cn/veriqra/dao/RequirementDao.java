package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.Requirement;
import java.util.List;
import java.util.Optional;

/** Caller owns the connection/transaction. No authorization or workflow transitions. */
public interface RequirementDao {
    Optional<Requirement> findById(Long id);
    /** Requires an outer transaction; locks the parent for coordinated child writes. */
    Optional<Requirement> findByIdForUpdate(Long id);
    Optional<Requirement> findByKey(Long projectId, Long keyNo);
    List<Requirement> listByProject(Long projectId);
    /** Ignores generated id/timestamps/version. Write and readback in an outer transaction. */
    Requirement insert(Requirement value);
    /** Uses id + lockVersion; projectId/keyNo/creator/createdAt remain immutable. */
    Requirement update(Requirement value);
}
