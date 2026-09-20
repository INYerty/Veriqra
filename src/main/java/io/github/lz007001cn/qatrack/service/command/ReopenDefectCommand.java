package io.github.lz007001cn.qatrack.service.command;

public record ReopenDefectCommand(Long defectId, Long failureAttemptId,
                                  Long assigneeId, Integer lockVersion) { }
