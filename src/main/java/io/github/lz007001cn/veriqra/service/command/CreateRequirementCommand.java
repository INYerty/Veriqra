package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.Priority;

public record CreateRequirementCommand(Long projectId, String title, String description, Priority priority) { }
