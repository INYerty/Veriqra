package io.github.lz007001cn.veriqra.service.importing;

import io.github.lz007001cn.veriqra.model.TestAttemptStatus;

/** One normalized testcase from a JUnit XML report. */
public record JUnitTestResult(int entryIndex, AutomationIdentityKey identity, TestAttemptStatus status,
                              Long durationMs, String comment, String failureMessage) { }
