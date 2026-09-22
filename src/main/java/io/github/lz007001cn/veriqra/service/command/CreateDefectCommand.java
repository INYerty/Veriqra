package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.*;

public record CreateDefectCommand(Long projectId, Long failureAttemptId, String title,
                                  String description, DefectSeverity severity, Priority priority,
                                  Long assigneeId) { }
