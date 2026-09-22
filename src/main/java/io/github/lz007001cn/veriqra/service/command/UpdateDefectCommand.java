package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.*;

public record UpdateDefectCommand(Long defectId, String title, String description,
                                  DefectSeverity severity, Priority priority,
                                  Long assigneeId, Integer lockVersion) { }
