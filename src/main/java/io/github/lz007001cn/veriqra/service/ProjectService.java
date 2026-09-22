package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.Project;
import io.github.lz007001cn.veriqra.service.command.*;

public interface ProjectService {
    java.util.List<Project> list(Long actorUserId);
    Project create(Long actorUserId, CreateProjectCommand command);
    Project get(Long actorUserId, Long projectId);
    Project update(Long actorUserId, UpdateProjectCommand command);
    Project archive(Long actorUserId, Long projectId, Integer lockVersion);
}
