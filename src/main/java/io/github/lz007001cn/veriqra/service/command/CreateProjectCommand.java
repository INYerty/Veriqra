package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.ProjectRole;

/** Optional initial member fields must either both be present or both be absent. */
public record CreateProjectCommand(String projectKey, String name, String description,
                                   Long initialMemberUserId, ProjectRole initialMemberRole) { }
