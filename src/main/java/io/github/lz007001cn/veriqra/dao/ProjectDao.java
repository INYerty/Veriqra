package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.Project;
import java.util.Optional;

/** Row persistence without project authorization, counters or membership business rules. */
public interface ProjectDao {
    java.util.List<Project> listAll();
    Optional<Project> findById(Long id);
    /** Requires an outer transaction; coordinates project-scoped writes without blocking other readers. */
    Optional<Project> findByIdForShare(Long id);
    /** Requires an outer transaction; used for project state changes such as archive. */
    Optional<Project> findByIdForUpdate(Long id);
    Optional<Project> findByKey(String projectKey);
    /** Generated ID, timestamps and version come from MySQL; those input fields are ignored. */
    Project insert(Project project);
    /** Updates name/description/status using id + lockVersion; key/creator/createdAt are immutable. */
    Project update(Project project);
}
