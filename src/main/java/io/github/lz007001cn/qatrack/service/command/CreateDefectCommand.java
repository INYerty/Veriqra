package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.*;

public record CreateDefectCommand(Long projectId, Long failureAttemptId, String title,
                                  String description, DefectSeverity severity, Priority priority,
                                  Long assigneeId) { }
