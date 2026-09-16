package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.*;

public record UpdateRequirementCommand(Long requirementId, String title, String description,
                                       Priority priority, RequirementStatus status, Integer lockVersion) { }
