package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.*;

public record UpdateRequirementCommand(Long requirementId, String title, String description,
                                       Priority priority, RequirementStatus status, Integer lockVersion) { }
