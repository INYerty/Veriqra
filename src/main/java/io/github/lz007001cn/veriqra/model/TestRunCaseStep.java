package io.github.lz007001cn.veriqra.model;

/** Immutable step snapshot, never synchronized with current test_steps. */
public record TestRunCaseStep(Long testRunCaseId, Integer stepOrder, String action, String expectedResult) { }
