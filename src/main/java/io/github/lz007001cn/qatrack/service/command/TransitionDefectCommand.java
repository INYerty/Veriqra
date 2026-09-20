package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.DefectStatus;

public record TransitionDefectCommand(Long defectId, DefectStatus targetStatus,
                                      String resolutionNote, Integer lockVersion) { }
