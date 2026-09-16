package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.Project;
import io.github.lz007001cn.qatrack.service.command.*;

public interface ProjectService {
    Project create(Long actorUserId, CreateProjectCommand command);
    Project get(Long actorUserId, Long projectId);
    Project update(Long actorUserId, UpdateProjectCommand command);
    Project archive(Long actorUserId, Long projectId, Integer lockVersion);
}
