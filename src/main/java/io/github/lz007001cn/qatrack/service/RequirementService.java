package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.model.Requirement;
import io.github.lz007001cn.qatrack.service.command.*;
import java.util.List;

public interface RequirementService {
    Requirement create(Long actorUserId, CreateRequirementCommand command);
    Requirement get(Long actorUserId, Long requirementId);
    List<Requirement> listByProject(Long actorUserId, Long projectId);
    Requirement update(Long actorUserId, UpdateRequirementCommand command);
}
