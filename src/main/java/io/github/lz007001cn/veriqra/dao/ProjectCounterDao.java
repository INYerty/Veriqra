package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.*;
import java.util.List;
import java.util.Optional;

/** Counter row access only; project creation initializes four rows in its outer transaction. */
public interface ProjectCounterDao {
    ProjectCounter insert(ProjectCounter value);
    Optional<ProjectCounter> find(Long projectId, CounterEntityType entityType);
    List<ProjectCounter> listByProject(Long projectId);
    /** Requires autoCommit=false. Locks an existing row, returns its old nextValue and increments it.
     * Allocation and the asset insert must commit/rollback together. No formatting, lazy creation or retry. */
    Long allocateNext(Long projectId, CounterEntityType entityType);
}
