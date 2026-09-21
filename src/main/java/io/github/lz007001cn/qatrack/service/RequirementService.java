package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.Requirement;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface RequirementService {
    Requirement get(Long actorUserId, Long projectId, Long requirementId);
    Requirement update(Long actorUserId, Long projectId, UpdateRequirementCommand command);
    Requirement create(Long actorUserId, CreateRequirementCommand command);
    Requirement get(Long actorUserId, Long requirementId);
    List<Requirement> listByProject(Long actorUserId, Long projectId);
    Requirement update(Long actorUserId, UpdateRequirementCommand command);
}
