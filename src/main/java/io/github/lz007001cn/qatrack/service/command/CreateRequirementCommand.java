package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.Priority;

public record CreateRequirementCommand(Long projectId, String title, String description, Priority priority) { }
