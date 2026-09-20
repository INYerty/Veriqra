package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.Defect;
import java.util.*;

/** Defect row only; transition rules and authorization belong to the caller. Caller owns connection/transaction. */
public interface DefectDao {
    Optional<Defect> findById(Long id);
    /** Current locking read for Service-owned status/evidence transactions. */
    Optional<Defect> findByIdForUpdate(Long id);
    Optional<Defect> findByKey(Long projectId, Long keyNo);
    List<Defect> listByProject(Long projectId);
    /** Ignores generated fields; insert and readback belong in an outer transaction. */
    Defect insert(Defect value);
    /** Uses id + lockVersion; ownership, origin, business key and creation metadata stay unchanged. */
    Defect update(Defect value);
}
