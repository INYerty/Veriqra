package io.github.lz007001cn.qatrack.service.importing;

import io.github.lz007001cn.qatrack.model.TestAttemptStatus;

/** One normalized testcase from a JUnit XML report. */
public record JUnitTestResult(int entryIndex, AutomationIdentityKey identity, TestAttemptStatus status,
                              Long durationMs, String comment, String failureMessage) { }
