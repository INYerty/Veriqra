package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.DefectStatus;

public record TransitionDefectCommand(Long defectId, DefectStatus targetStatus,
                                      String resolutionNote, Integer lockVersion) { }
