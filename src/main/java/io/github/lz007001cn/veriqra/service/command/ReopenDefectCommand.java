package io.github.lz007001cn.veriqra.service.command;

public record ReopenDefectCommand(Long defectId, Long failureAttemptId,
                                  Long assigneeId, Integer lockVersion) { }
