package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Evidence association to a specific Attempt; not the current TestCase outcome. */
public record TestAttemptDefect(Long attemptId, Long defectId, Long linkedBy, LocalDateTime linkedAt) { }
