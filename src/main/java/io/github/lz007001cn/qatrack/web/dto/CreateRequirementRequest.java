package io.github.lz007001cn.qatrack.web.dto;

import io.github.lz007001cn.qatrack.model.Priority;

public record CreateRequirementRequest(String title, String description, Priority priority) { }
